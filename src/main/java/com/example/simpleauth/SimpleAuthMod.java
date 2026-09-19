package com.example.simpleauth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@Mod(SimpleAuthMod.MODID)
public class SimpleAuthMod {
    public static final String MODID = "simpleauth";
    public SimpleAuthMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(Events.class);
    }
    public static final class Config {
        public static final ForgeConfigSpec SPEC;
        public static final ForgeConfigSpec.IntValue MIN_PASSWORD_LENGTH;
        public static final ForgeConfigSpec.IntValue MAX_PASSWORD_LENGTH;
        public static final ForgeConfigSpec.IntValue MAX_LOGIN_ATTEMPTS;
        public static final ForgeConfigSpec.IntValue LOCKOUT_SECONDS;
        public static final ForgeConfigSpec.IntValue LOGIN_TIMEOUT_SECONDS;
        static {
            ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
            b.push("simpleauth");
            MIN_PASSWORD_LENGTH = b.comment("密码最小长度").defineInRange("minPasswordLength", 6, 4, 64);
            MAX_PASSWORD_LENGTH = b.comment("密码最大长度").defineInRange("maxPasswordLength", 32, 8, 64);
            MAX_LOGIN_ATTEMPTS = b.comment("密码错误达到此次数后踢出并临时锁定").defineInRange("maxLoginAttempts", 5, 1, 100);
            LOCKOUT_SECONDS = b.comment("临时锁定时长（秒），重启服务器会清空全部锁定").defineInRange("lockoutSeconds", 300, 10, 3600);
            LOGIN_TIMEOUT_SECONDS = b.comment("进服后多少秒未完成注册/登录则踢出").defineInRange("loginTimeoutSeconds", 120, 30, 1800);
            b.pop();
            SPEC = b.build();
        }
    }
    public static final class Events {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("simpleauth").resolve("accounts.json");
        private static final Map<UUID, Account> ACCOUNTS = new ConcurrentHashMap<>();
        private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
        private static final Map<UUID, Long> LOCKED_UNTIL = new ConcurrentHashMap<>();
        private static final long PROMPT_INTERVAL_MS = 5000L;
        private static int minLen() { return Config.MIN_PASSWORD_LENGTH.get(); }
        private static int maxLen() { return Config.MAX_PASSWORD_LENGTH.get(); }
        private static int maxTries() { return Config.MAX_LOGIN_ATTEMPTS.get(); }
        private static long timeoutMs() { return Config.LOGIN_TIMEOUT_SECONDS.get() * 1000L; }
        private static long lockoutMs() { return Config.LOCKOUT_SECONDS.get() * 1000L; }
        private static final Set<String> ALLOWED_CMDS = Set.of("login", "l", "register", "reg", "simpleauth");
        private static final class Account {
            String name;
            String salt;
            String hash;
            long regDate;
            long lastLogin;
        }
        private static final class Pending {
            double x, y, z;
            long joinMillis;
            int tries;
            boolean registered;
            List<ItemStack> invSnapshot;
            long lastPromptMillis;
        }
        @SubscribeEvent
        public static void onServerStarting(ServerStartingEvent e) {
            load();
        }
        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent e) {
            save();
        }
        @SubscribeEvent
        public static void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            UUID id = p.getUUID();
            Long unlockAt = LOCKED_UNTIL.get(id);
            if (unlockAt != null) {
                long remainMs = unlockAt - System.currentTimeMillis();
                if (remainMs > 0) {
                    p.connection.disconnect(Component.literal("§c密码错误次数过多，账号已临时锁定，请约 " + (remainMs / 1000 + 1) + " 秒后再试。"));
                    return;
                }
                LOCKED_UNTIL.remove(id);
            }
            Account acc = ACCOUNTS.get(id);
            Pending pend = new Pending();
            pend.x = p.getX();
            pend.y = p.getY();
            pend.z = p.getZ();
            pend.joinMillis = System.currentTimeMillis();
            pend.tries = 0;
            pend.registered = acc != null;
            pend.invSnapshot = snapshotInventory(p.getInventory());
            pend.lastPromptMillis = System.currentTimeMillis();
            PENDING.put(id, pend);
            p.getAbilities().invulnerable = true;
            p.onUpdateAbilities();
            if (acc != null) {
                p.sendSystemMessage(Component.literal("§e欢迎回来！检测到该账号已注册，请登录：§f/login <密码>§e（快捷：§f/l§e）"));
            } else {
                p.sendSystemMessage(Component.literal("§e检测到你是首次进入本服务器，请先注册账号：§f/register <密码> <确认密码>§e（快捷：§f/reg§e）"));
            }
        }
        @SubscribeEvent
        public static void onQuit(PlayerEvent.PlayerLoggedOutEvent e) {
            PENDING.remove(e.getEntity().getUUID());
            save();
        }
        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Pending> en : PENDING.entrySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
                if (p == null) continue;
                Pending pend = en.getValue();
                Inventory inv = p.getInventory();
                List<ItemStack> snap = pend.invSnapshot;
                boolean invDirty = false;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (!ItemStack.matches(snap.get(i), inv.getItem(i))) {
                        inv.setItem(i, snap.get(i).copy());
                        invDirty = true;
                    }
                }
                if (invDirty) {
                    inv.setChanged();
                    p.containerMenu.broadcastChanges();
                }
                if (now - pend.lastPromptMillis > PROMPT_INTERVAL_MS) {
                    pend.lastPromptMillis = now;
                    p.sendSystemMessage(Component.literal(pend.registered
                            ? "§e欢迎回来！检测到该账号已注册，请登录：§f/login <密码>§e（快捷：§f/l§e）"
                            : "§e检测到你是首次进入本服务器，请先注册账号：§f/register <密码> <确认密码>§e（快捷：§f/reg§e）"));
                }
                p.teleportTo(pend.x, pend.y, pend.z);
                p.setDeltaMovement(Vec3.ZERO);
                p.fallDistance = 0;
                if (now - pend.joinMillis > timeoutMs()) {
                    p.connection.disconnect(Component.literal("§c登录超时，请重新进入服务器。"));
                    PENDING.remove(en.getKey());
                }
            }
        }
        @SubscribeEvent(receiveCanceled = true)
        public static void onBreak(BlockEvent.BreakEvent e) {
            if (!isAuthed(e.getPlayer())) e.setCanceled(true);
        }
        @SubscribeEvent(receiveCanceled = true)
        public static void onPlace(BlockEvent.EntityPlaceEvent e) {
            if (e.getEntity() instanceof Player pl && !isAuthed(pl)) e.setCanceled(true);
        }
        @SubscribeEvent(receiveCanceled = true)
        public static void onInteract(PlayerInteractEvent e) {
            if (!isAuthed(e.getEntity())) e.setCanceled(true);
        }
        @SubscribeEvent(receiveCanceled = true)
        public static void onAttack(AttackEntityEvent e) {
            if (!isAuthed(e.getEntity())) e.setCanceled(true);
        }
        @SubscribeEvent(receiveCanceled = true)
        public static void onPickup(EntityItemPickupEvent e) {
            if (!isAuthed(e.getEntity())) e.setCanceled(true);
        }
        @SubscribeEvent(receiveCanceled = true)
        public static void onToss(ItemTossEvent e) {
            if (!isAuthed(e.getPlayer())) e.setCanceled(true);
        }
        @SubscribeEvent(receiveCanceled = true)
        public static void onChat(ServerChatEvent e) {
            ServerPlayer p = e.getPlayer();
            if (p != null && !isAuthed(p)) {
                e.setCanceled(true);
                p.sendSystemMessage(Component.literal("§c请先登录后再聊天。"));
            }
        }
        @SubscribeEvent(receiveCanceled = true)
        public static void onCommand(CommandEvent e) {
            CommandSourceStack src = e.getParseResults().getContext().getSource();
            if (!(src.getEntity() instanceof ServerPlayer p)) return;
            if (isAuthed(p)) return;
            String raw = e.getParseResults().getReader().getString();
            String base = raw.split(" ")[0].replaceFirst("^/", "").toLowerCase();
            if (!ALLOWED_CMDS.contains(base)) {
                e.setCanceled(true);
            }
        }
        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent e) {
            var d = e.getDispatcher();
            d.register(registerNode("register"));
            d.register(registerNode("reg"));
            d.register(loginNode("login"));
            d.register(loginNode("l"));
            d.register(Commands.literal("changepassword")
                    .then(Commands.argument("old", StringArgumentType.word())
                    .then(Commands.argument("new", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        return cmdChangePassword(p,
                                StringArgumentType.getString(ctx, "old"),
                                StringArgumentType.getString(ctx, "new"));
                    }))));
            d.register(Commands.literal("simpleauth")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("reload")
                            .executes(ctx -> { load(); ctx.getSource().sendSystemMessage(Component.literal("§a已重载账号数据。")); return 1; }))
                    .then(Commands.literal("forcelogin")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "player");
                                        ServerPlayer t = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
                                        if (t == null) { ctx.getSource().sendSystemMessage(Component.literal("§c玩家不在线。")); return 0; }
                                        finishAuth(t);
                                        ctx.getSource().sendSystemMessage(Component.literal("§a已强制登录 " + name));
                                        return 1;
                                    })))
                    .then(Commands.literal("unregister")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "player");
                                        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
                                        UUID id = target != null ? target.getUUID() : offlineUUID(name);
                                        Account acc = ACCOUNTS.remove(id);
                                        if (acc == null) {
                                            ctx.getSource().sendSystemMessage(Component.literal("§c该玩家没有注册记录。"));
                                            return 0;
                                        }
                                        if (target != null) {
                                            PENDING.remove(id);
                                            target.connection.disconnect(Component.literal("§c你的账号已被管理员注销，请重新注册账号后再进入服务器。"));
                                        }
                                        save();
                                        ctx.getSource().sendSystemMessage(Component.literal("§a已注销 " + name + " 的账号，其下次进服需重新注册。"));
                                        return 1;
                                    })))
                    .then(Commands.literal("resetpass")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .then(Commands.argument("newpass", StringArgumentType.word())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "player");
                                                String newPw = StringArgumentType.getString(ctx, "newpass");
                                                if (newPw.length() < minLen() || newPw.length() > maxLen()) {
                                                    ctx.getSource().sendSystemMessage(Component.literal("§c新密码长度须在 " + minLen() + "-" + maxLen() + " 之间。"));
                                                    return 0;
                                                }
                                                ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
                                                UUID id = target != null ? target.getUUID() : offlineUUID(name);
                                                Account acc = ACCOUNTS.get(id);
                                                if (acc == null) {
                                                    ctx.getSource().sendSystemMessage(Component.literal("§c该玩家没有注册记录。"));
                                                    return 0;
                                                }
                                                String[] out = hash(newPw);
                                                acc.salt = out[0];
                                                acc.hash = out[1];
                                                save();
                                                ctx.getSource().sendSystemMessage(Component.literal("§a已将 " + name + " 的密码重置，请线下告知其新密码。"));
                                                return 1;
                                            }))))
                    .executes(ctx -> { ctx.getSource().sendSystemMessage(Component.literal("/simpleauth reload|forcelogin|unregister|resetpass；玩家：/register /login（别名 /reg /l） /changepassword <旧> <新>")); return 1; }));
        }
        private static LiteralArgumentBuilder<CommandSourceStack> registerNode(String name) {
            return Commands.literal(name)
                    .then(Commands.argument("password", StringArgumentType.word())
                    .then(Commands.argument("confirm", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        return cmdRegister(p,
                                StringArgumentType.getString(ctx, "password"),
                                StringArgumentType.getString(ctx, "confirm"));
                    })));
        }
        private static LiteralArgumentBuilder<CommandSourceStack> loginNode(String name) {
            return Commands.literal(name)
                    .then(Commands.argument("password", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        return cmdLogin(p, StringArgumentType.getString(ctx, "password"));
                    }));
        }
        private static int cmdRegister(ServerPlayer p, String pw, String confirm) {
            UUID id = p.getUUID();
            Pending pend = PENDING.get(id);
            if (pend == null || pend.registered) {
                p.sendSystemMessage(Component.literal("§c你无需注册。"));
                return 0;
            }
            if (!pw.equals(confirm)) { p.sendSystemMessage(Component.literal("§c两次输入的密码不一致。")); return 0; }
            if (pw.length() < minLen() || pw.length() > maxLen()) {
                p.sendSystemMessage(Component.literal("§c密码长度须在 " + minLen() + "-" + maxLen() + " 之间。"));
                return 0;
            }
            String[] out = hash(pw);
            Account acc = new Account();
            acc.name = p.getGameProfile().getName();
            acc.salt = out[0];
            acc.hash = out[1];
            acc.regDate = System.currentTimeMillis();
            acc.lastLogin = acc.regDate;
            ACCOUNTS.put(id, acc);
            save();
            finishAuth(p);
            p.sendSystemMessage(Component.literal("§a注册成功，已自动登录。"));
            return 1;
        }
        private static int cmdLogin(ServerPlayer p, String pw) {
            UUID id = p.getUUID();
            Pending pend = PENDING.get(id);
            if (pend == null) { p.sendSystemMessage(Component.literal("§c你已登录。")); return 0; }
            if (!pend.registered) { p.sendSystemMessage(Component.literal("§c请先使用 /register 注册。")); return 0; }
            Account acc = ACCOUNTS.get(id);
            if (acc == null) { p.sendSystemMessage(Component.literal("§c账号不存在，请注册。")); return 0; }
            if (!verify(pw, acc.salt, acc.hash)) {
                pend.tries++;
                if (pend.tries >= maxTries()) {
                    LOCKED_UNTIL.put(id, System.currentTimeMillis() + lockoutMs());
                    p.connection.disconnect(Component.literal("§c密码错误次数过多，账号已临时锁定。"));
                    PENDING.remove(id);
                } else {
                    p.sendSystemMessage(Component.literal("§c密码错误，剩余尝试次数：" + (maxTries() - pend.tries)));
                }
                return 0;
            }
            acc.lastLogin = System.currentTimeMillis();
            save();
            finishAuth(p);
            p.sendSystemMessage(Component.literal("§a登录成功。"));
            return 1;
        }
        private static int cmdChangePassword(ServerPlayer p, String oldPw, String newPw) {
            Account acc = ACCOUNTS.get(p.getUUID());
            if (acc == null) { p.sendSystemMessage(Component.literal("§c你还没有注册。")); return 0; }
            if (!verify(oldPw, acc.salt, acc.hash)) { p.sendSystemMessage(Component.literal("§c原密码错误。")); return 0; }
            if (newPw.length() < minLen() || newPw.length() > maxLen()) {
                p.sendSystemMessage(Component.literal("§c新密码长度须在 " + minLen() + "-" + maxLen() + " 之间。"));
                return 0;
            }
            String[] out = hash(newPw);
            acc.salt = out[0];
            acc.hash = out[1];
            save();
            p.sendSystemMessage(Component.literal("§a密码修改成功。"));
            return 1;
        }
        private static void finishAuth(ServerPlayer p) {
            PENDING.remove(p.getUUID());
            p.getAbilities().invulnerable = false;
            p.onUpdateAbilities();
        }
        private static boolean isAuthed(Player p) {
            return p == null || !PENDING.containsKey(p.getUUID());
        }
        private static List<ItemStack> snapshotInventory(Inventory inv) {
            List<ItemStack> list = new ArrayList<>(inv.getContainerSize());
            for (int i = 0; i < inv.getContainerSize(); i++) list.add(inv.getItem(i).copy());
            return list;
        }
        private static UUID offlineUUID(String name) {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }
        private static String[] hash(String password) {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] dk = pbkdf2(password, salt);
            return new String[]{
                    Base64.getEncoder().encodeToString(salt),
                    Base64.getEncoder().encodeToString(dk)
            };
        }
        private static boolean verify(String password, String saltB64, String hashB64) {
            byte[] salt = Base64.getDecoder().decode(saltB64);
            byte[] expect = Base64.getDecoder().decode(hashB64);
            return MessageDigest.isEqual(expect, pbkdf2(password, salt));
        }
        private static byte[] pbkdf2(String password, byte[] salt) {
            try {
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 128);
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } catch (Exception ex) {
                throw new IllegalStateException("PBKDF2 初始化失败", ex);
            }
        }
        private static void load() {
            ACCOUNTS.clear();
            try {
                if (!Files.exists(FILE)) return;
                JsonObject root = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), JsonObject.class);
                if (root == null || !root.has("accounts")) return;
                JsonObject all = root.getAsJsonObject("accounts");
                for (String k : all.keySet()) {
                    JsonObject o = all.getAsJsonObject(k);
                    Account a = new Account();
                    a.name = o.get("name").getAsString();
                    a.salt = o.get("salt").getAsString();
                    a.hash = o.get("hash").getAsString();
                    a.regDate = o.get("regDate").getAsLong();
                    a.lastLogin = o.get("lastLogin").getAsLong();
                    ACCOUNTS.put(UUID.fromString(k), a);
                }
            } catch (Exception ex) {
                System.err.println("[SimpleAuth] 读取账号文件失败: " + ex.getMessage());
            }
        }
        private static void save() {
            try {
                Files.createDirectories(FILE.getParent());
                JsonObject root = new JsonObject();
                JsonObject all = new JsonObject();
                for (Map.Entry<UUID, Account> en : ACCOUNTS.entrySet()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", en.getValue().name);
                    o.addProperty("salt", en.getValue().salt);
                    o.addProperty("hash", en.getValue().hash);
                    o.addProperty("regDate", en.getValue().regDate);
                    o.addProperty("lastLogin", en.getValue().lastLogin);
                    all.add(en.getKey().toString(), o);
                }
                root.add("accounts", all);
                Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
                Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
                Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                System.err.println("[SimpleAuth] 保存账号文件失败: " + ex.getMessage());
            }
        }
    }
}
