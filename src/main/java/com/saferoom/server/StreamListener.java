package com.saferoom.server;

import com.saferoom.grpc.UDPHoleImpl;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.ChannelOption;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class StreamListener extends Thread {
	public static int grpcPort = SafeRoomServer.grpcPort;
	private Server server;
	private static SslContext sslContext; 
	private static String SERVER_CERT_PATH = "certs/server.crt";
	private static String SERVER_KEY_PATH = "certs/server.key";

	static {
		try {
			System.out.println("Loading TLS certificates...");
			sslContext = GrpcSslContexts.configure(
				SslContextBuilder.forServer(
					new File(SERVER_CERT_PATH),
					new File(SERVER_KEY_PATH)
				)
			).build();
			System.out.println("TLS certificates loaded successfully");
		} catch (Exception e) {
			System.err.println("TLS Certificates couldn't be loaded: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException("Failed to load TLS certificates", e);
		}
	}
	public void run() {
		try {
			System.out.println("Starting gRPC Server on port " + grpcPort + "...");
			
			// NettyServerBuilder ile optimize edilmiş server
			server = NettyServerBuilder
					.forAddress(new InetSocketAddress(grpcPort))
					.sslContext(sslContext)
					.addService(new UDPHoleImpl())
					// Socket options
					.withChildOption(ChannelOption.SO_REUSEADDR, true)
					.withOption(ChannelOption.SO_REUSEADDR, true)
					.withChildOption(ChannelOption.SO_KEEPALIVE, true)  // TCP Keep-Alive
					// gRPC Keep-Alive settings
					.keepAliveTime(30, TimeUnit.SECONDS)  // Client'tan 30 saniyede bir ping bekle
					.keepAliveTimeout(10, TimeUnit.SECONDS)  // 10 saniye timeout
					.permitKeepAliveTime(20, TimeUnit.SECONDS)  // Client minimum 20 saniyede bir ping gönderebilir
					.permitKeepAliveWithoutCalls(true)  // Aktif çağrı olmadan da keepalive kabul et
					// Message size limits
					.maxInboundMessageSize(10 * 1024 * 1024)  // 10MB max incoming message
					.maxConnectionIdle(Long.MAX_VALUE, TimeUnit.DAYS)  // Connection'ı süresiz tut
					.build()
					.start();
			
			System.out.println("✓ gRPC Server successfully started on port " + grpcPort);
			System.out.println("✓ TLS/SSL enabled");
			System.out.println("✓ Keep-Alive enabled (30s interval)");
			System.out.println("✓ SO_REUSEADDR enabled");
			
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
			System.err.println("Server Builder [ERROR]: " + e.getMessage());
			e.printStackTrace();
		}
	}
	
	private void shutdown() throws InterruptedException {
		if (server != null) {
			server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
		}
	}
}
