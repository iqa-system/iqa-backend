-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `username` VARCHAR(50) NOT NULL UNIQUE,
  `password` VARCHAR(255) NOT NULL,
  `register_time` DATETIME default CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 图片评估历史记录表
DROP TABLE IF EXISTS `assess_record`;
CREATE TABLE `assess_record` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `user_id` INT NOT NULL,
  `filename` VARCHAR(255) NOT NULL,
  `base64` LONGTEXT NOT NULL,
  `quality_score` DOUBLE,
  `scene` INT,
  `distortions` JSON NOT NULL,
  `assess_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_assess_record_user_time` (`user_id`, `assess_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;