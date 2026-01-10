package com.saferoom.server;

public class SafeRoomServer {
	public static String ServerIP = "34.159.9.239";
	public static int grpcPort = 443;

	public static void main(String[] args) throws Exception {
		StreamListener Stream = new StreamListener();

		// Start gRPC server
		Stream.start();

		System.out.println("SafeRoom Server started:");
		System.out.println("   gRPC Server: " + ServerIP + ":" + grpcPort);
		System.out.println("   WebRTC Signaling: via gRPC");
		System.out.println("   Press Ctrl+C to shutdown gracefully");

		// Keep main thread alive
		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {
			System.out.println("Server interrupted, shutting down...");
		}
	}
}
