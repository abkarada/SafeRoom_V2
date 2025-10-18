package com.saferoom.server;

public class SafeRoomServer {
	public static String ServerIP = "35.198.64.68";
	public static int grpcPort = 443;
	public static int udpPort1 = 45000;

	
	public static void main(String[] args) throws Exception{
		StreamListener Stream = new StreamListener();
		
		Stream.start();
		
		System.out.println("SafeRoom Server started:");
		System.out.println("gRPC Server: " + ServerIP + ":" + grpcPort);
		System.out.println(" Press Ctrl+C to shutdown gracefully");
		
		// Keep main thread alive
		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {
			System.out.println("Server interrupted, shutting down...");
		}
	}
}
