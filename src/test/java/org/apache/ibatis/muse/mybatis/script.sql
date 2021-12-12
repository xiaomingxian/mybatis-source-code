use muse;

CREATE TABLE `tb_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `msg_id` varchar(255) NOT NULL DEFAULT '' COMMENT '消息ID',
  `status` int(11) NOT NULL DEFAULT '-1' COMMENT '消息状态，-1-待发送，0-发送中，1-发送失败 2-已发送',
  `content` varchar(255)  NOT NULL DEFAULT '' COMMENT '消息内容',
  `deleted` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除  1-已删除',
  `create_time` DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00' COMMENT '创建时间',
  `update_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  INDEX `index_msg_id` (`msg_id`) USING BTREE,
  INDEX `index_create_time` (`create_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8 COMMENT='消息表';

