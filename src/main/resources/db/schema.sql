-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `username` VARCHAR(50) NOT NULL UNIQUE,
  `password` VARCHAR(255) NOT NULL,
  `email` VARCHAR(255) NULL UNIQUE,
  `role` VARCHAR(20) NOT NULL DEFAULT 'USER',
  `register_time` DATETIME default CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文件元数据表
CREATE TABLE `file_entry` (
`id` INT PRIMARY KEY AUTO_INCREMENT,
`user_id` INT NOT NULL,
`filename` VARCHAR(64) NOT NULL,
`extname` VARCHAR(20) NOT NULL,
`directory` VARCHAR(1024) NOT NULL,
`size` BIGINT NOT NULL,
`upload_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
INDEX `idx_file_entry_user_time` (`user_id`, `upload_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 图片评估历史记录表
-- 旧历史记录在本次升级中直接清空, 不做Base64迁移
CREATE TABLE `assess_record` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `user_id` INT NOT NULL,
  `filename` VARCHAR(255) NOT NULL,
  `file_entry_id` INT NOT NULL,
  `quality_score` DOUBLE,
  `scene` INT,
  `distortions` JSON NOT NULL,
  `assess_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_assess_record_user_time` (`user_id`, `assess_time`),
  INDEX `idx_assess_record_file_entry_id` (`file_entry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
