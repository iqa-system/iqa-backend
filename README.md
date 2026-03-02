# iqa-backend

## 说明
- 新增了基础的注册/登录/刷新Token接口
- SQL建表语句见 `src/main/resources/db/schema.sql`

## 运行准备
- 确保 MySQL 已创建数据库 `iqa_system`
- 将 `schema.sql` 执行到数据库中

## 启动
```powershell
mvn spring-boot:run
```

