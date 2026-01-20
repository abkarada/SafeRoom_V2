package com.saferoom.server;

import com.saferoom.grpc.UDPHoleImpl;
import com.saferoom.rooms.server.RoomServiceImpl;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.ChannelOption;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class StreamListener extends Thread {
	// SafeRoomServer'dan port bilgisini al
	public static int grpcPort = SafeRoomServer.grpcPort;
	private Server server;

	public void run() {
		try {
			// NettyServerBuilder ile SO_REUSEADDR aktif et
			server = NettyServerBuilder
					.forAddress(new InetSocketAddress(grpcPort))
					.addService(new UDPHoleImpl())
					.addService(new RoomServiceImpl()) // Register Rooms v1 Service
					.withChildOption(ChannelOption.SO_REUSEADDR, true)
					.withOption(ChannelOption.SO_REUSEADDR, true)
					.keepAliveTime(20, TimeUnit.SECONDS)
					.keepAliveTimeout(10, TimeUnit.SECONDS)
					.permitKeepAliveTime(10, TimeUnit.SECONDS)
					.permitKeepAliveWithoutCalls(true)

					.build()
					.start();

			System.out.println("gRPC Server Started on port " + grpcPort + " (SO_REUSEADDR enabled)");

			// Graceful shutdown hook
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				System.err.println("*** Shutting down gRPC server (JVM shutdown)");
				try {
					StreamListener.this.shutdown();
				} catch (InterruptedException e) {
					e.printStackTrace(System.err);
				}
				System.err.println("*** gRPC server shut down");
			}));

			server.awaitTermination();
		} catch (Exception e) {
			System.err.println("Server Builder [ERROR]: " + e);
			e.printStackTrace();
		}
	}

	private void shutdown() throws InterruptedException {
		if (server != null) {
			server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
		}
	}
}
