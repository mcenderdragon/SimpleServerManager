package mcenderdragon.minecraft.server;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class MainServerManager
{
	public static long MAX_STOP_COMMAND_WAIT_TIME = 600000L;
	public static long MAX_KILL_WAIT_TIME = 60000L;

	public static boolean repeat;
	
	public static void main(String[] args)
	{
		String config = "server_manager.xml";
		if (args.length >= 1)
		{
			config = args[0];
		}
		System.out.println("Using config file (first param):" + config);
		try
		{
			
			DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
			
			Document doc = builder.parse(new File(config));
			Element root = doc.getDocumentElement();
		   
			if (!root.hasAttribute("repeat"))
			{
				throw new IllegalArgumentException("root node needs \"repeat\" attribute (boolean) ");
			}
			repeat = Boolean.parseBoolean(root.getAttribute("repeat"));
			
			if (root.hasAttribute("stop_time"))
			{
				MAX_STOP_COMMAND_WAIT_TIME = Long.decode(root.getAttribute("stop_time"));
			}
			if (root.hasAttribute("kill_time"))
			{
				MAX_KILL_WAIT_TIME = Long.decode(root.getAttribute("kill_time"));
			}
			
			System.out.println("MAX_STOP_COMMAND_WAIT_TIME (stop_time attribute)=" + MAX_STOP_COMMAND_WAIT_TIME);
			System.out.println("MAX_KILL_WAIT_TIME (kill_time)=" + MAX_KILL_WAIT_TIME);
			
			NodeList list = root.getElementsByTagName("task");
			ArrayList<TimedTask> taks = new ArrayList<TimedTask>(list.getLength());
			int i = 0;
			while (i < list.getLength()) 
			{
				Node item = list.item(i);
				if (item.getParentNode() == root) {
					taks.add(MainServerManager.parse(item));
				}
				++i;
			}
			
			do
			{
				taks.forEach(Runnable::run);
				taks.forEach(t -> t.reset());
			}
			while (repeat);
		}
		catch (FileNotFoundException e) {
			System.out.println(e);
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		catch (ParserConfigurationException e) {
			e.printStackTrace();
		}
		catch (SAXException e) {
			e.printStackTrace();
		}
	}

	private static TimedTask parse(Node n) 
	{
		if (n.getNodeType() == 1) 
		{
			Element elm = (Element)n;
			NodeList list = elm.getElementsByTagName("start");
			TimedTask task = new TimedTask(MainServerManager.getNodeValue(elm, "start"));
			task.setStopCommand(MainServerManager.getNodeValue(elm, "stop"));
			if (elm.hasAttribute("time")) 
			{
				task.setMaxTime(Long.decode(elm.getAttribute("time")));
			}
			if (elm.hasAttribute("cron")) 
			{
				task.setCronTrigger(new CronTrigger(elm.getAttribute("cron")));
			}
			list = elm.getElementsByTagName("task");
			int i = 0;
			while (i < list.getLength()) 
			{
				Element subtask;
				Node child = list.item(i);
				if (child.getNodeType() == 1 && (subtask = (Element)child).hasAttribute("trigger")) 
				{
					String trig = subtask.getAttribute("trigger");
					if (trig.equals("success")) 
					{
						task.command_on_success = MainServerManager.parse(subtask);
					} else if (trig.equals("failure")) 
					{
						task.command_on_failure = MainServerManager.parse(subtask);
					}
				}
				++i;
			}
			return task;
		}
		return null;
	}

	private static String getNodeValue(Element elm, String tag)
	{
		NodeList list = elm.getElementsByTagName(tag);
		if (list.getLength() == 0) {
			return null;
		}
		StringBuilder builder = new StringBuilder();
		int i = 0;
		while (i < list.getLength()) {
			Node n = list.item(i);
			if (n.getParentNode() == elm) {
				if (i > 0) {
					builder.append("\r\n").append((char) 0);
				}
				builder.append(n.getTextContent());
			}
			++i;
		}
		return builder.toString();
	}

	public static class CronTrigger implements Predicate<Calendar>
	{
		Predicate<Calendar> minuite;
		Predicate<Calendar> hour;
		Predicate<Calendar> day_of_month;
		Predicate<Calendar> month;
		Predicate<Calendar> day_of_week;

		public CronTrigger(String cron) 
		{
			String[] parts = cron.split(" ");
			if (parts.length != 5) 
			{
				throw new IllegalArgumentException("5 expressions are required see the official cron syntax");
			}
			this.minuite = this.parseString(12, parts[0]);
			this.hour = this.parseString(11, parts[1]);
			this.day_of_month = this.parseString(5, parts[2]);
			this.month = this.parseString(2, parts[3]);
			this.day_of_week = this.parseString(7, parts[4]);
		}

		@Override
		public boolean test(Calendar t)
		{
			boolean r = this.minuite.and(this.hour).and(this.day_of_month).and(this.month).and(this.day_of_week).test(t);
			return r;
		}

		public Predicate<Calendar> parseString(final int field, String toPrase)
		{
			if (toPrase.equals("*")) 
			{
				return c -> true;
			}
			if (toPrase.contains(",")) 
			{
				String[] parts = toPrase.split(",");
				Predicate<Calendar> pred = c -> true;
				int i = 0;
				while (i < parts.length) {
					pred = pred.and(this.parseString(field, parts[i]));
					++i;
				}
				return pred;
			}
			if (toPrase.contains("-")) 
			{
				String[] parts = toPrase.split("-");
				if (parts.length != 2) 
				{
					throw new IllegalArgumentException("<from>-<to>");
				}
				final int start = Integer.decode(parts[0]);
				final int end = Integer.decode(parts[1]);
				return new Predicate<Calendar>()
				{
					@Override
					public boolean test(Calendar t)
					{
						int f = t.get(field);
						return start <= f && f <= end;
					}
					
				};
			}
			final int time = Integer.decode(toPrase);
			return new Predicate<Calendar>()
			{
				@Override
				public boolean test(Calendar t)
				{
					int f = t.get(field);
					return f == time;
				}
				
			};
		}
	}

	public static class TimedTask implements Runnable
	{
		private String start_command;
		private String stop_command = null;
		private boolean wait_until_exit = true;
		TimedTask command_on_failure = null;
		TimedTask command_on_success = null;
		private long max_runtime = -1L;
		private CronTrigger cron_trigger = null;
		private boolean started;
		private boolean running;
		private Process process;

		public TimedTask(String command)
		{
			this.start_command = command;
			this.reset();
		}

		public void reset() {
			this.process = null;
			this.started = false;
			this.running = false;
		}

		public void setMaxTime(long seconds) 
		{
			this.wait_until_exit = false;
			this.max_runtime = seconds * 1000L;
		}

		public void setCronTrigger(CronTrigger trigger) 
		{
			this.wait_until_exit = false;
			this.cron_trigger = trigger;
		}

		public void setStopCommand(String command) 
		{
			this.stop_command = command;
		}

		public Process getOrCreateProcess() throws IOException 
		{
			if (this.process != null) 
			{
				return this.process;
			}
			ProcessBuilder builder = new ProcessBuilder(this.start_command.split(" "));
			builder.environment().put("MSYSTEM", "MINGW-managment");//this is to disable jline support; if jline is active it will crash later on and we cant communicate with the server
			builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
			builder.redirectError(ProcessBuilder.Redirect.INHERIT);
			builder.redirectInput(this.wait_until_exit ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
			System.out.println("[INFO] Now starting \"" + this.start_command + "\"");
			this.process = builder.start();
			this.started = true;
			this.running = true;
			return this.process;
		}

		public boolean isStarted() 
		{
			return this.started;
		}

		public boolean isRunning() throws IOException 
		{
			if (this.started) 
			{
				Process p = this.getOrCreateProcess();
				this.running = p.isAlive();
				return this.running;
			}
			return false;
		}

		public void postRunning() 
		{
			if (this.isStarted() && this.process != null && !this.process.isAlive()) {
				TimedTask task;
				int exit = this.process.exitValue();
				System.out.println("[INFO] \"" + this.start_command + "\" exited with:" + exit);
				task = exit == 0 ? this.command_on_success : this.command_on_failure;
				if (task != null) 
				{
					task.join();
				}
			}
		}

		/*
		 * Enabled aggressive block sorting
		 * Enabled unnecessary exception pruning
		 * Enabled aggressive exception aggregation
		 */
		public void join() 
		{
			try 
			{
				Process p = this.getOrCreateProcess();
				if (this.wait_until_exit) 
				{
					p.waitFor();
				}
				else
				{
					long end = System.currentTimeMillis() + this.max_runtime;
					if (this.cron_trigger == null) 
					{
						this.sleep(this.max_runtime);
					}
					else
					{
						while (p.isAlive() && System.currentTimeMillis() < end && !this.cron_trigger.test(Calendar.getInstance()))
						{
							this.sleep(10000L);
						}
					}
					stop();
				}
				this.postRunning();
			}
			catch (IOException e) 
			{
				e.printStackTrace();
			}
			catch (InterruptedException e) 
			{
				e.printStackTrace();
			}
		}
		
		public void stop() throws IOException, InterruptedException
		{
			if (isRunning() && this.stop_command != null) 
			{
				process.getOutputStream().write(this.stop_command.getBytes());
				process.getOutputStream().write("\r\n".getBytes());
				process.getOutputStream().write(0);
				process.getOutputStream().flush();
				this.sleep(MainServerManager.MAX_STOP_COMMAND_WAIT_TIME);
				
				if (process.isAlive()) 
				{
					System.err.println("[WARN] stop command did not work, killing the process");
					process.destroy();
					this.sleep(MainServerManager.MAX_KILL_WAIT_TIME);
					
					if (process.isAlive()) 
					{
						System.err.println("[WARN] killing process did not work, force killing process");
						process.destroyForcibly();
						Thread.sleep(1000L);
						
						if (process.isAlive()) 
						{
							System.err.println("[ERROR] force kill did not work Process " + process + " is still alive!");
							System.out.println("Shutting down Tool, hopefully this also kills the sub process!");
							System.exit(1);
						}
					}
				}
			}
		}

		private void pipeInput()
		{
			try 
			{
				if(System.in.available() > 0)
				{
					byte[] input = new byte[1024];
					StringBuilder builder = new StringBuilder(System.in.available());
					while (System.in.available() > 0) 
					{
						int r = System.in.read(input);
						builder.append(new String(input, 0, r));
					}
					String command = builder.toString();
					if(command.charAt(0) == '!')
					{
						command = command.substring(1).trim();
						command(command);
					}
					else
					{
						this.process.getOutputStream().write(command.getBytes());
						this.process.getOutputStream().write(0);
					}
				}
				this.process.getOutputStream().flush();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}

		private void sleep(long millis) throws InterruptedException 
		{
			long end = millis + System.currentTimeMillis();
			while (System.currentTimeMillis() < end) 
			{
				this.pipeInput();
				Thread.sleep(100L);
				if (!this.process.isAlive()) 
					break;
			}
		}

		@Override
		public void run() 
		{
			this.join();
		}
		
		public void command(String s)
		{
			try
			{
				if(s.equals("help"))
				{
					System.out.println("commands:");
					System.out.println("\tabort(stops current process and continues script)");
					System.out.println("\texit(stops current process and ServerManager)");
				}
				else if(s.equals("abort"))
				{
					this.stop();
				}
				else if(s.equals("exit"))
				{
					this.stop();
					MainServerManager.repeat = false;
					System.exit(0);
				}
				else
				{
					System.out.println("Unknown command, try !help.");
				}
			}
			catch(Exception e)
			{
				e.printStackTrace();
			}
		}
	}
}