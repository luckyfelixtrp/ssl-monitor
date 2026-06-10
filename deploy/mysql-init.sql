-- ============================================================
--  SSL Monitor - MySQL 初始化脚本
--  在 MySQL 中执行一次即可（应用启动时 JPA 也会自动建表，
--  但建议先创建库与账号）。
-- ============================================================

CREATE DATABASE IF NOT EXISTS ssl_monitor
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- 创建专用账号（按需修改密码）
CREATE USER IF NOT EXISTS 'ssl_monitor'@'%' IDENTIFIED BY 'ssl_monitor_pwd';
GRANT ALL PRIVILEGES ON ssl_monitor.* TO 'ssl_monitor'@'%';
FLUSH PRIVILEGES;

-- 表结构（JPA ddl-auto=update 也会创建，这里给出参考）
USE ssl_monitor;

CREATE TABLE IF NOT EXISTS domains (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    host        VARCHAR(253) NOT NULL,
    port        INT NOT NULL DEFAULT 443,
    remark      VARCHAR(200) DEFAULT '',
    created_at  DATETIME(6) NOT NULL,
    UNIQUE KEY uk_host_port (host, port)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS check_results (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain_id    BIGINT NOT NULL,
    checked_at   DATETIME(6) NOT NULL,
    ok           BIT(1) NOT NULL,
    error        TEXT,
    days_left    INT,
    not_after    VARCHAR(255),
    grade        VARCHAR(8),
    score        INT,
    tls_version  VARCHAR(32),
    cipher       VARCHAR(128),
    reachable    BIT(1),
    status_code  INT,
    response_ms  INT,
    payload      LONGTEXT NOT NULL,
    KEY idx_check_domain (domain_id, checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
