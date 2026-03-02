## Plan: 注册登录与JWT拦截

梳理现有工程缺失的用户与认证基础设施，新增用户表与持久化、认证服务与控制器、JWT 生成校验与拦截器，再把接口信息同步到 `openapi.yaml`。方案围绕 MyBatis-Plus 和 jjwt 实现最小可用登录注册与 access token 刷新，同时保证参数校验提示为中文，并在新增代码中补充详尽中文注释。

### Steps 4
1. 明确用户模型与数据表，新增用户实体与 Mapper 到 [src/main/java/org/wlow/iqabackend/data](src/main/java/org/wlow/iqabackend/data) 与对应包，规划必要字段和索引。  
2. 设计认证 DTO、服务与 JWT 工具类，集中处理注册、登录、刷新逻辑与中文校验信息。  
3. 新增 `AuthController` 并挂载 `/auth/sign-up`、`/auth/login`、`/auth/refresh`，完善中文注释与异常处理。  
4. 配置 token 拦截器与 MVC 注册，设置放行路径与鉴权失败响应，同时更新 [openapi.yaml](openapi.yaml)。
5. 用户实体类PO字段包括: id(主键), username, password(已加密), registerTime
6. 创建MySQL用户表user, 字段包括: id(主键), username, password(已加密), register_time
7. 密码加密策略采用BCrypt，使用 `spring-security-crypto` 库实现，确保密码安全存储。
