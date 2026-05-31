CREATE DATABASE IF NOT EXISTS saferoom;
USE saferoom;

CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    salt VARCHAR(255) NOT NULL,
    verification_code VARCHAR(100),
    is_verified BOOLEAN DEFAULT FALSE,
    is_blocked BOOLEAN DEFAULT FALSE,
    last_login TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_stats (
    username VARCHAR(255) PRIMARY KEY,
    rooms_created INT DEFAULT 0,
    rooms_joined INT DEFAULT 0,
    files_shared INT DEFAULT 0,
    messages_sent INT DEFAULT 0,
    activity_score DECIMAL(10,2) DEFAULT 0.0,
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_activities (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255),
    activity_type VARCHAR(100),
    activity_description TEXT,
    activity_data TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS blocked_users (
    username VARCHAR(255) PRIMARY KEY,
    reason VARCHAR(255),
    ip_address VARCHAR(45),
    blocked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS login_attempts (
    username VARCHAR(255) PRIMARY KEY,
    attempt_count INT DEFAULT 1,
    last_attempt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS registration_attempts (
    username VARCHAR(255) PRIMARY KEY,
    attempts INT DEFAULT 1,
    last_attempt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS verification_attempts (
    username VARCHAR(255) PRIMARY KEY,
    attempts INT DEFAULT 1,
    last_attempt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS friendships (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user1 VARCHAR(255),
    user2 VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user1) REFERENCES users(username) ON DELETE CASCADE,
    FOREIGN KEY (user2) REFERENCES users(username) ON DELETE CASCADE,
    UNIQUE KEY unique_friendship (user1, user2)
);

CREATE TABLE IF NOT EXISTS friend_requests (
    id INT AUTO_INCREMENT PRIMARY KEY,
    sender VARCHAR(255),
    receiver VARCHAR(255),
    message TEXT,
    status VARCHAR(50) DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender) REFERENCES users(username) ON DELETE CASCADE,
    FOREIGN KEY (receiver) REFERENCES users(username) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS STUN_INFO (
    username VARCHAR(255) PRIMARY KEY,
    Public_IP VARCHAR(45),
    Public_Port INT,
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);
