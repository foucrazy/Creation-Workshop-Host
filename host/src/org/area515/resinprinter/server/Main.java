package org.area515.resinprinter.server;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.websocket.server.ServerContainer;

import org.area515.resinprinter.discover.BroadcastManager;
import org.area515.resinprinter.notification.NotificationManager;
import org.area515.resinprinter.security.JettySecurityUtils;
import org.area515.resinprinter.stream.ProgressiveDownloadServlet;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;

/*
 * References:
 * http://news-anand.blogspot.com/2012/05/today-i-am-going-tell-you-how-to-create.html
 * http://alvinalexander.com/java/jwarehouse/jetty-6.1.9/etc/realm.properties.shtml
 * http://www.eclipse.org/jetty/documentation/9.1.4.v20140401/embedded-examples.html#embedded-secured-hello-handler
 */

public class Main {
	public static ExecutorService GLOBAL_EXECUTOR = Executors.newScheduledThreadPool(15);

	public static void main(String[] args) throws Exception {

		int port = HostProperties.Instance().getPrinterHostPort();
		/*
		 * Sequence
		 * Setup ResourceHandler for html files
		 * Setup ServletContextHandler for rest services
		 * Add serviceContext, resource_handler, and DetaultHandler to the server (must be in that order)
		 * Initialize application
		 * Start server
		 */

		final Server server = new Server(port);
		 
		/*
		 * Setup ResourceHandler for html files
		 */
		
		 // Create the ResourceHandler. It is the object that will handle the request for a given file. It is
        // a Jetty Handler object so it is suitable for chaining with other handlers as you will see in other examples.
        ResourceHandler resource_handler = new ResourceHandler();
        // Configure the ResourceHandler. Setting the resource base indicates where the files should be served out of.
        // In this example it is the current directory but it can be configured to anything that the jvm has access to.
        resource_handler.setDirectoriesListed(true);
        resource_handler.setWelcomeFiles(new String[]{ "index.html" });
        resource_handler.setResourceBase("resources");
		
        
        /*
         * Setup ServletContextHandler for rest services
         */
        
        // For services
        ServletContextHandler serviceContext = new ServletContextHandler(ServletContextHandler.SESSIONS);
		serviceContext.setContextPath("/services");
		ServletHolder servicesHolder = new ServletHolder(new HttpServletDispatcher());
		servicesHolder.setInitParameter("javax.ws.rs.Application","org.area515.resinprinter.server.ApplicationConfig");
		serviceContext.addServlet(servicesHolder, "/*");
		
        // For Raspberry Pi video
        ServletContextHandler videoContext = new ServletContextHandler(ServletContextHandler.SESSIONS);
        videoContext.setContextPath("/video");
		videoContext.addServlet(ProgressiveDownloadServlet.class, "/*");		
		
		// Add the ResourceHandler to the server.
		HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { videoContext, serviceContext, resource_handler, new DefaultHandler() });
        server.setHandler(handlers);

        String externallyAccessableIP = HostProperties.Instance().getExternallyAccessableName();
		if (externallyAccessableIP == null) {
			//Don't do this: 127.0.0.1 on Linux!
			//getSetup().externallyAccessableIP = InetAddress.getLocalHost().getHostAddress();
			
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements()) {
				NetworkInterface currentInterface = interfaces.nextElement();
				if (currentInterface.isLoopback() || currentInterface.isVirtual()) {
					continue;
				}
				
				Enumeration<InetAddress> addresses = currentInterface.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress address = addresses.nextElement();
					if (address.getAddress().length == 4) {//Make sure it's ipv4
						externallyAccessableIP = address.getHostAddress();
					}
				}
			}
		}
		
		//Determine if we are going to use SSL and user authentication
		if (HostProperties.Instance().isUseSSL()) {
			JettySecurityUtils.secureContext(externallyAccessableIP, serviceContext, server);
		}
		
		ServerContainer container = WebSocketServerContainerInitializer.configureContext(serviceContext);
		NotificationManager.start(container);
		
		//Start server before we start broadcasting!
		try {
			server.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		//Start broadcasting server
		BroadcastManager.start(new URI("http" + (HostProperties.Instance().isUseSSL()?"s://":"://") + externallyAccessableIP + ":" + port));
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					BroadcastManager.shutdown();
				} catch (Exception e) {
					e.printStackTrace();
				}
				try {
					NotificationManager.shutdown();
				} catch (Exception e) {
					e.printStackTrace();
				}
				try {
					server.stop();
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					server.destroy();
					System.out.println("Shutdown Complete");
				}
			}
		});
		
		//Wait in the Main method until we are shutdown by the OS
		try {
			server.join();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}