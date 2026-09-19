# simpleauth
只支持1.20.1forge的登录mod，只需要服务器安装，客户端无需安装
mod相关指令
1.玩家可用指令
注册账号:/register <密码> <确认密码>（或/reg <密码> <确认密码>）；
登录:/login <密码>(或/l <密码>)--密码输入错误上限默认 5 次，超出次数会被踢出并临时锁定（默认 300 秒，锁定期内进服即被踢）；登录成功则解除冻结、取消无敌、恢复行动；玩家进入服务器如果超过120秒后未进行注册或登录的操作就会被踢出；
玩家自助改密:/changepassword <旧密码> <新密码>--仅已认证玩家可用
2.管理员可用指令
帮助:/simpleauth--显示全部子命令用法；
重载:/simpleauth reload--重新从 accounts.json 读取账号数据到内存；
强制放行玩家:/simpleauth forcelogin <玩家名>--把在线玩家直接标记为已认证（解除冻结、取消无敌），玩家不在线则失败；
注销玩家账号:/simpleauth unregister <玩家名>；
重置玩家账号密码:/simpleauth resetpass <玩家名> <新密码>
