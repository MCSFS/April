package cn.mcsfs.april;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.util.*;

public class April extends JavaPlugin implements Listener {

    // 添加提示开关状态
    private boolean notificationsEnabled = true;

    // 存储玩家欠款数据：玩家UUID -> (物品类型 -> 欠款数量)
    private final Map<UUID, Map<Material, Integer>> debtMap = new HashMap<>();
    private final Random random = new Random();
    private static final double FAIL_CHANCE = 0.7; // 70%失败概率

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("2025愚人节插件已启用！");

        // 启动下雨时燃烧伤害的任务
        startRainDamageTask();

        Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
            setupScoreboard();
            startScoreboardUpdateTask();
        });

        getCommand("aqk").setExecutor(new DebtCommandExecutor()); // 添加命令注册

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new DebtPlaceholder(this).register();
            getLogger().info("已挂钩PlaceholderAPI");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("2025愚人节插件已禁用！");
    }

    // 添加状态控制方法
    public boolean areNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean enabled) {
        this.notificationsEnabled = enabled;
    }


    //==================== 添加PlaceholderAPI支持 ====================
    // 添加获取总欠款的方法（供PlaceholderAPI调用）
    public int getTotalDebt(UUID uuid) {
        return debtMap.getOrDefault(uuid, Collections.emptyMap())
                .values().stream().mapToInt(Integer::intValue).sum();
    }

    // 添加PlaceholderAPI扩展类
    private class DebtPlaceholder extends PlaceholderExpansion {
        private final April plugin;

        public DebtPlaceholder(April plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getIdentifier() {
            return "aqk";
        }

        @Override
        public String getAuthor() {
            return "你的名字";
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (player == null) return "0";

            if (identifier.equalsIgnoreCase("player_aqk")) {
                return String.valueOf(plugin.getTotalDebt(player.getUniqueId()));
            }
            return null;
        }
    }

    //==================== 欠款计分板 ====================
    private class DebtCommandExecutor implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            // 处理 start/stop 子命令
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop")) {
                    if (!sender.hasPermission("april.admin")) {
                        sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
                        return true;
                    }

                    boolean enable = args[0].equalsIgnoreCase("start");
                    setNotificationsEnabled(enable);
                    sender.sendMessage(ChatColor.GREEN + "系统提示已" + (enable ? "开启" : "关闭"));
                    return true;
                }
            }

            // 原有显示欠款逻辑
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家可以使用此命令");
                return true;
            }

            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();

            // 检查是否有欠款记录
            if (!debtMap.containsKey(uuid) || debtMap.get(uuid) == null || debtMap.get(uuid).isEmpty()) {
                player.sendMessage(ChatColor.GREEN + "你当前没有任何欠款");
                return true;
            }

            Map<Material, Integer> debts = debtMap.get(uuid);

            // 构建欠款信息
            StringBuilder message = new StringBuilder();
            message.append(ChatColor.GOLD).append("=== 你的欠款清单 ===\n");
            debts.forEach((material, amount) -> {
                String name = getChineseName(material);
                message.append(ChatColor.YELLOW)
                        .append(name)
                        .append(ChatColor.WHITE)
                        .append(": ")
                        .append(ChatColor.RED)
                        .append(amount)
                        .append("个\n");
            });
            message.append(ChatColor.GOLD).append("===================");

            player.sendMessage(message.toString());
            return true;
        }
    }

    // 添加计分板初始化方法
    private void setupScoreboard() {
        try {
            // 确保获取到有效的计分板管理器
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                throw new IllegalStateException("Scoreboard manager not available");
            }

            debtScoreboard = manager.getNewScoreboard();
            debtObjective = debtScoreboard.registerNewObjective("debtRank", "dummy",
                    ChatColor.GOLD + ChatColor.BOLD.toString() + "欠款排行榜");
            debtObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } catch (Exception e) {
            getLogger().severe("初始化计分板失败: " + e.getMessage());
            // 创建备用计分板
            debtScoreboard = Bukkit.getServer().getScoreboardManager().getMainScoreboard();
            debtObjective = debtScoreboard.getObjective("debtRank") != null ?
                    debtScoreboard.getObjective("debtRank") :
                    debtScoreboard.registerNewObjective("debtRank", "dummy",
                            ChatColor.GOLD + ChatColor.BOLD.toString() + "欠款排行榜");
        }
    }

    // 添加计分板更新任务
    private void startScoreboardUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                updateDebtScoreboard();
            }
        }.runTaskTimer(this, 0, UPDATE_INTERVAL);
    }

    // 添加计分板更新逻辑
    private void updateDebtScoreboard() {
        // 清除旧数据
        for (String entry : debtScoreboard.getEntries()) {
            debtScoreboard.resetScores(entry);
        }

        // 收集所有玩家的总欠款
        Map<String, Integer> totalDebts = new HashMap<>();
        for (Map.Entry<UUID, Map<Material, Integer>> entry : debtMap.entrySet()) {
            int total = getTotalDebt(entry.getKey()); // 使用新方法
            if (total > 0) {
                Player player = Bukkit.getPlayer(entry.getKey());
                String name = (player != null) ? player.getName() :
                        Bukkit.getOfflinePlayer(entry.getKey()).getName();
                totalDebts.put(name, total);
            }
        }

        // 按欠款数量排序
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totalDebts.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        // 更新计分板（最多显示15个玩家）
        int count = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            if (count++ >= 15) break;
            Score score = debtObjective.getScore(entry.getKey());
            score.setScore(entry.getValue());
        }

        // 向所有玩家显示计分板
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(debtScoreboard);
        }
    }

    // ==================== 在类中添加的常量 ====================
    private static final String PEARL_DEATH_META = "EnderPearlDeath";
    private static final Set<EntityType> ARTHROPODS = new HashSet<>(Arrays.asList(
            EntityType.SPIDER,
            EntityType.CAVE_SPIDER,
            EntityType.BEE,
            EntityType.SILVERFISH,
            EntityType.ENDERMITE
    ));
    private static final double PEARL_THROW_SPEED = 6; // 投掷速度倍率
    private static final double PEARL_DAMAGE = 20.0;
    private Scoreboard debtScoreboard;
    private Objective debtObjective;
    private final int UPDATE_INTERVAL = 1 * 20; // 每1秒更新一次

    // ==================== 数据存储 ====================
    private final Map<UUID, EnderPearl> activePearls = new HashMap<>(); // 玩家-珍珠映射

    // ==================== 食物效果配置 ====================
    private static final Map<Material, Integer> FOOD_VALUES = new HashMap<>();
    static {
        // 食物基础饱食度值（正数表示原版增益值）
        FOOD_VALUES.put(Material.APPLE, 4);
        FOOD_VALUES.put(Material.BAKED_POTATO, 5);
        FOOD_VALUES.put(Material.BEEF, 3);
        FOOD_VALUES.put(Material.BREAD, 5);
        FOOD_VALUES.put(Material.CARROT, 3);
        FOOD_VALUES.put(Material.CHICKEN, 2);
        FOOD_VALUES.put(Material.CHORUS_FRUIT, 4);
        FOOD_VALUES.put(Material.COOKED_BEEF, 8);
        FOOD_VALUES.put(Material.COOKED_CHICKEN, 6);
        FOOD_VALUES.put(Material.COOKED_COD, 5);
        FOOD_VALUES.put(Material.COOKED_MUTTON, 6);
        FOOD_VALUES.put(Material.COOKED_PORKCHOP, 8);
        FOOD_VALUES.put(Material.COOKED_RABBIT, 5);
        FOOD_VALUES.put(Material.COOKED_SALMON, 6);
        FOOD_VALUES.put(Material.COOKIE, 2);
        FOOD_VALUES.put(Material.DRIED_KELP, 1);
        FOOD_VALUES.put(Material.ENCHANTED_GOLDEN_APPLE, 4);
        FOOD_VALUES.put(Material.GOLDEN_APPLE, 4);
        FOOD_VALUES.put(Material.GOLDEN_CARROT, 6);
        FOOD_VALUES.put(Material.HONEY_BOTTLE, 6);
        FOOD_VALUES.put(Material.MELON_SLICE, 2);
        FOOD_VALUES.put(Material.MUSHROOM_STEW, 6);
        FOOD_VALUES.put(Material.PORKCHOP, 3);
        FOOD_VALUES.put(Material.POTATO, 1);
        FOOD_VALUES.put(Material.PUFFERFISH, 1);
        FOOD_VALUES.put(Material.PUMPKIN_PIE, 8);
        FOOD_VALUES.put(Material.RABBIT_STEW, 10);
        FOOD_VALUES.put(Material.BEETROOT_SOUP, 6);
        FOOD_VALUES.put(Material.SWEET_BERRIES, 2);
        FOOD_VALUES.put(Material.TROPICAL_FISH, 1);
        FOOD_VALUES.put(Material.ROTTEN_FLESH, 4); // 腐肉特殊处理
        // 添加更多食物...
    }

    // ==================== 末影珍珠改造部分 ====================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPearlThrow(PlayerInteractEvent event) {
        // 检查右键使用末影珍珠
        if (event.getAction() == Action.RIGHT_CLICK_AIR ||
                event.getAction() == Action.RIGHT_CLICK_BLOCK) {

            ItemStack item = event.getItem();
            if (item != null && item.getType() == Material.ENDER_PEARL) {
                Player player = event.getPlayer();

                // 取消原版投掷行为
                event.setCancelled(true);

                // 添加元数据标记
                player.setMetadata(PEARL_DEATH_META, new FixedMetadataValue(this, true));

                // 消耗物品（非创造模式）
                if (player.getGameMode() != GameMode.CREATIVE) {
                    item.setAmount(item.getAmount() - 1);
                }

                // 给玩家投掷初速度
                Vector direction = player.getLocation().getDirection();
                player.setVelocity(direction.multiply(PEARL_THROW_SPEED));

                // 生成追踪用珍珠实体
                EnderPearl pearl = (EnderPearl) player.getWorld().spawnEntity(
                        player.getLocation(),
                        EntityType.ENDER_PEARL
                );
                pearl.setShooter(player);
                pearl.setGravity(false);  // 禁用重力
                pearl.setInvulnerable(true); // 设为无敌
                pearl.setVisibleByDefault(false); // 对玩家不可见

                activePearls.put(player.getUniqueId(), pearl);

                // 添加延迟任务清除标记（5秒后）
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (player.isOnline() && player.hasMetadata(PEARL_DEATH_META)) {
                            player.removeMetadata(PEARL_DEATH_META, April.this);
                        }
                    }
                }.runTaskLater(this, 20 * 5); // 5秒后清除

                // 添加粒子特效
                player.spawnParticle(
                        Particle.PORTAL,
                        player.getLocation(),
                        30,
                        0.5, 0.5, 0.5,
                        0.5
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player &&
                event.getCause() == DamageCause.FALL) {

            Player player = (Player) event.getEntity();

            // 检查是否为末影珍珠摔落
            if (activePearls.containsKey(player.getUniqueId())) {
                EnderPearl pearl = activePearls.remove(player.getUniqueId());

                // 执行传送逻辑
                pearl.teleport(player.getLocation());
                player.damage(PEARL_DAMAGE); // 造成等效珍珠伤害

                // 播放音效和粒子
                player.getWorld().playSound(
                        player.getLocation(),
                        Sound.ENTITY_ENDERMAN_TELEPORT,
                        1.0f,
                        1.0f
                );
                player.spawnParticle(
                        Particle.EXPLOSION,
                        player.getLocation(),
                        10
                );

                // 清理珍珠实体
                pearl.remove();
                event.setCancelled(true); // 取消原版摔落伤害

                // 标记为末影珍珠死亡
                player.setMetadata(PEARL_DEATH_META, new FixedMetadataValue(this, true));
            }
        }
    }



    @EventHandler
    public void onPearlHit(ProjectileHitEvent event) {
        // 禁用所有原版末影珍珠效果
        if (event.getEntity() instanceof EnderPearl) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }

    // ==================== 食物效果部分 ====================
    @EventHandler
    public void onPlayerEat(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Material food = event.getItem().getType();

        if (food == Material.ROTTEN_FLESH) {
            // 腐肉：移除饥饿效果
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.removePotionEffect(PotionEffectType.HUNGER);
                }
            }.runTaskLater(this, 1);
        } else if (FOOD_VALUES.containsKey(food)) {
            // 其他食物：双倍扣除饱食度
            int value = FOOD_VALUES.get(food);
            new BukkitRunnable() {
                @Override
                public void run() {
                    int newFood = Math.max(0, player.getFoodLevel() - value * 2);
                    player.setFoodLevel(newFood);
                    if (areNotificationsEnabled()) {
                        player.sendMessage("§c进食消耗了" + (value * 2) + "点饱食度");
                    }
                }
            }.runTaskLater(this, 1);
        }
    }

    // ==================== 摔落保护功能 ====================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerFall(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FALL) {
            if (event.getEntity() instanceof Player) {
                Player player = (Player) event.getEntity();
                ItemStack boots = player.getInventory().getBoots();

                if (boots != null && boots.containsEnchantment(Enchantment.FEATHER_FALLING)) {
                    // 完全覆盖原版计算逻辑
                    double fallDistance = player.getFallDistance();

                    // 新的伤害计算规则
                    if (fallDistance > 0.5) {
                        // 每超过0.5格造成1点伤害（半颗心）
                        event.setDamage((fallDistance - 0.5) * 4);
                    } else {
                        // 0.5格以下取消伤害
                        event.setCancelled(true);
                    }

                    // 强制重置原版盔甲保护计算
                    event.setDamage(DamageModifier.ARMOR, 0);
                }
            }
        }
    }

    // ==================== 节肢杀手附魔陷阱功能 ====================
    @EventHandler(priority = EventPriority.HIGH)
    public void onArthropodAttack(EntityDamageByEntityEvent event) {
        // 检查攻击者是否为玩家
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            ItemStack weapon = player.getInventory().getItemInMainHand();

            // 检查武器是否带有节肢杀手附魔
            if (weapon != null && weapon.containsEnchantment(Enchantment.BANE_OF_ARTHROPODS)) {
                // 检查被攻击者是否为节肢生物
                if (isArthropod(event.getEntityType()) && event.getEntity() instanceof LivingEntity) {
                    LivingEntity target = (LivingEntity) event.getEntity();

                    // 保存原始生命值
                    double originalHealth = target.getHealth();

                    // 强制设置伤害为1点（触发生物仇恨）
                    event.setDamage(1.0);

                    // 在生物位置生成绿色粒子效果
                    spawnGreenParticles(target);

                    // 在事件处理后立即恢复生命值
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        // 检查生物是否仍然存活
                        if (!target.isDead()) {
                            double currentHealth = target.getHealth();
                            // 如果生物没有其他伤害来源，恢复到原始生命值
                            if (currentHealth <= originalHealth) {
                                target.setHealth(originalHealth);
                            }
                        }
                    }, 1L); // 延迟1tick执行

                    // 生成蜘蛛网陷阱
                    generateWebTrap(player);
                }
            }
        }
    }

    // 生成绿色粒子效果
    private void spawnGreenParticles(LivingEntity entity) {
        Location loc = entity.getLocation().add(0, 1, 0); // 在生物中心位置
        entity.getWorld().spawnParticle(
                Particle.HAPPY_VILLAGER,  // 使用村民高兴的绿色粒子
                loc,
                15,  // 数量
                0.5, 0.5, 0.5, // 偏移量
                0.1  // 额外参数
        );
    }

    // 判断生物类型是否为节肢生物
    private boolean isArthropod(EntityType type) {
        return ARTHROPODS.contains(type);
    }

    // 生成蜘蛛网陷阱
    private void generateWebTrap(Player player) {
        Random rand = new Random();
        int webCount = rand.nextInt(9) + 6; // 生成6-9个蜘蛛网

        for (int i = 0; i < webCount; i++) {
            // 获取玩家周围1-2格内的随机位置
            Location loc = player.getLocation().clone()
                    .add(rand.nextInt(2)-1,  // X轴偏移 (-1~1)
                            rand.nextInt(2),    // Y轴偏移 (0~1)
                            rand.nextInt(2)-1); // Z轴偏移 (-1~1)

            // 确保目标位置是空气或可替换方块
            Block targetBlock = loc.getBlock();
            if (targetBlock.getType().isAir() ||
                    targetBlock.getType() == Material.SHORT_GRASS ||
                    targetBlock.getType() == Material.TALL_GRASS) {
                targetBlock.setType(Material.COBWEB);
                // 在生成蜘蛛网后5秒自动清除
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (targetBlock.getType() == Material.COBWEB) {
                        targetBlock.setType(Material.AIR);
                    }
                }, 20*5); // 5秒后清除
            }
        }

        // 提示玩家
        if (areNotificationsEnabled()) {
            player.sendMessage("§c节肢生物的粘液困住了你！");
        }
    }

// ==================== 下雨时燃烧伤害功能 ====================

    private void startRainDamageTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    if (world.hasStorm()) { // 检查世界是否下雨
                        for (Player player : world.getPlayers()) {
                            // 检查玩家是否在下雨的群系中
                            if (isPlayerInRainBiome(player)) {
                                // 如果玩家在岩浆中，直接跳过
                                if (isPlayerInLava(player)) {
                                    continue;
                                }

                                // 检查是否暴露在雨中
                                if (isPlayerExposedToRain(player)) {
                                    player.setFireTicks(100); // 施加燃烧效果
                                    player.damage(2.0); // 每次触发扣 2 点血
                                } else {
                                    // 如果玩家未被雨淋到，解除燃烧状态
                                    player.setFireTicks(0);
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L); // 每 1 秒检查一次
    }

    // 检查玩家是否在下雨的群系中
    private boolean isPlayerInRainBiome(Player player) {
        // 获取玩家所在的群系
        Biome biome = player.getLocation().getBlock().getBiome();

        // 检查群系是否会在下雨时下雨（排除沙漠、恶地等群系）
        return biome != Biome.DESERT && biome != Biome.SAVANNA && biome != Biome.SAVANNA_PLATEAU
                && biome != Biome.BADLANDS && biome != Biome.ERODED_BADLANDS
                && biome != Biome.WOODED_BADLANDS && biome != Biome.WINDSWEPT_SAVANNA;
    }

    // 优化后的遮挡检测方法
    private boolean isPlayerExposedToRain(Player player) {
        Location loc = player.getLocation();
        World world = player.getWorld();

        // 从玩家头顶开始检测到世界最高点
        for (int y = loc.getBlockY() + 1; y <= world.getMaxHeight(); y++) {
            Block block = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            // 如果遇到遮挡雨水的方块（完整方块）
            if (block.getType().isOccluding()) {
                return false;
            }
        }
        return true;
    }

    // 单独检测岩浆状态的方法
    private boolean isPlayerInLava(Player player) {
        Material blockType = player.getLocation().getBlock().getType();
        return blockType == Material.LAVA || blockType == Material.LAVA_CAULDRON;
    }

// ==================== 木剑攻击生物功能 ====================

    // 在类开头定义元数据键名
    private static final String META_KEY = "WoodenSwordSpawned";

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // 检查攻击者是否为玩家
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            ItemStack weapon = player.getInventory().getItemInMainHand();

            // 检查武器是否为木剑
            if (weapon != null && weapon.getType() == Material.WOODEN_SWORD) {
                // 检查被攻击者是否为生物（非玩家）且未被标记
                if (event.getEntity() instanceof LivingEntity
                        && !(event.getEntity() instanceof Player)
                        && !event.getEntity().hasMetadata(META_KEY)) { // 新增元数据检查

                    LivingEntity target = (LivingEntity) event.getEntity();
                    EntityType entityType = target.getType();

                    // 在被攻击的生物附近生成1-6只相同的生物
                    int count = random.nextInt(6) + 1;
                    for (int i = 0; i < count; i++) {
                        Entity spawnedEntity = target.getWorld().spawnEntity(target.getLocation(), entityType);
                        if (spawnedEntity instanceof LivingEntity) {
                            LivingEntity livingEntity = (LivingEntity) spawnedEntity;
                            livingEntity.setHealth(livingEntity.getMaxHealth());

                            // 给生成的生物添加元数据标记
                            livingEntity.setMetadata(META_KEY,
                                    new FixedMetadataValue(this, true)); // 新增元数据设置
                        }
                    }

                    // 提示玩家
                    if (areNotificationsEnabled()) {
                        player.sendMessage("§a你攻击了" + entityType.name() + "，并召唤了" + count + "只相同的生物！");
                    }
                }
            }
        }
    }
    // ==================== 混乱效果部分 ====================

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // 检查玩家是否进入水或岩浆
        Material blockType = player.getLocation().getBlock().getType();

        if (blockType == Material.WATER || blockType == Material.WATER_CAULDRON) {
            // 如果玩家进入水，模拟岩浆效果
            player.setFireTicks(100); // 让玩家燃烧 5 秒
            player.damage(2.0); // 每次触发扣 2 点血
        } else if (blockType == Material.LAVA || blockType == Material.LAVA_CAULDRON) {
            // 如果玩家进入岩浆，移除燃烧效果并给予生命恢复
            player.setFireTicks(0); // 移除燃烧效果
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1)); // 生命恢复
            player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 100, 1)); // 水下呼吸
            player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 100, 1)); // 海豚的祝福
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        // 阻止岩浆的默认伤害
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Material blockType = player.getLocation().getBlock().getType();

            if (blockType == Material.LAVA || blockType == Material.LAVA_CAULDRON) {
                event.setCancelled(true); // 取消岩浆伤害
            }
        }
    }
    // ==================== 修改死亡提示部分 ====================
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        // 检查是否为末影珍珠死亡
        if (player.hasMetadata(PEARL_DEATH_META)) {
            event.setDeathMessage(player.getName() + " 被末影珍珠丢死了！");
            player.removeMetadata(PEARL_DEATH_META, this);
        }
        // 保留原有的水中死亡检测
        else {
            Material blockType = player.getLocation().getBlock().getType();
            if (blockType == Material.WATER || blockType == Material.WATER_CAULDRON) {
                event.setDeathMessage(player.getName() + " 被水烧死了！");
            }
        }
    }


    // ==================== 霉运附魔部分 ====================

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();

        // 检查工具是否带有时运附魔
        if (tool != null && tool.containsEnchantment(Enchantment.FORTUNE)) {
            Material blockType = event.getBlock().getType();
            Material dropType = getDropMaterial(blockType);

            if (dropType != null && random.nextDouble() < FAIL_CHANCE) {
                // 取消掉落物
                event.setDropItems(false);

                // 更新欠款记录
                updateDebt(player.getUniqueId(), dropType, 1);

                // 发送提示
                player.sendMessage("§c喜报！你现在欠" +
                        getDebtCount(player.getUniqueId(), dropType) +
                        "个" + getChineseName(dropType) + "了");
            }
        }
    }

    @EventHandler
    public void onItemPickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        ItemStack itemStack = event.getItem().getItemStack();
        Material type = itemStack.getType();
        UUID uuid = player.getUniqueId();

        if (debtMap.containsKey(uuid) && debtMap.get(uuid).containsKey(type)) {
            int debt = debtMap.get(uuid).get(type);
            if (debt <= 0) return;

            // 获取实际拾取数量
            int pickedAmount = itemStack.getAmount();
            int reduceAmount = Math.min(pickedAmount, debt);

            // 更新欠款
            int newDebt = debt - reduceAmount;
            debtMap.get(uuid).put(type, newDebt);
            if (newDebt == 0) {
                debtMap.get(uuid).remove(type);
            }

            // 处理物品堆叠
            if (reduceAmount < pickedAmount) {
                // 创建剩余物品并生成新的掉落物
                ItemStack remaining = itemStack.clone();
                remaining.setAmount(pickedAmount - reduceAmount);

                // 生成新的物品实体
                Item remainingItem = player.getWorld().dropItem(
                        event.getItem().getLocation(),
                        remaining
                );
                remainingItem.setPickupDelay(0);
            }

            // 取消原事件并移除原物品
            event.setCancelled(true);
            event.getItem().remove();

            // 发送消息并更新计分板
            player.sendMessage("§6喜报！你现在欠" + newDebt +
                    "个" + getChineseName(type) + "了");
            updateDebtScoreboard();
        }
    }

    // 获取方块对应的掉落物类型
    private Material getDropMaterial(Material blockType) {
        switch (blockType) {
            case DIAMOND_ORE:
                return Material.DIAMOND;
            case DEEPSLATE_DIAMOND_ORE:
                return Material.DIAMOND;
            case COAL_ORE:
                return Material.COAL;
            case DEEPSLATE_COAL_ORE:
                return Material.COAL;
            case COPPER_ORE:
                return Material.RAW_COPPER;
            case DEEPSLATE_COPPER_ORE:
                return Material.RAW_COPPER;
            case GOLD_ORE:
                return Material.RAW_GOLD;
            case DEEPSLATE_GOLD_ORE:
                return Material.RAW_GOLD;
            case IRON_ORE:
                return Material.RAW_IRON;
            case DEEPSLATE_IRON_ORE:
                return Material.RAW_IRON;
            case EMERALD_ORE:
                return Material.EMERALD;
            case DEEPSLATE_EMERALD_ORE:
                return Material.EMERALD;
            case LAPIS_ORE:
                return Material.LAPIS_LAZULI;
            case DEEPSLATE_LAPIS_ORE:
                return Material.LAPIS_LAZULI;
            case NETHER_GOLD_ORE:
                return Material.GOLD_NUGGET;
            case NETHER_QUARTZ_ORE:
                return Material.QUARTZ;
            case ANCIENT_DEBRIS:
                return Material.ANCIENT_DEBRIS;
            case REDSTONE_ORE:
                return Material.REDSTONE;
            case DEEPSLATE_REDSTONE_ORE:
                return Material.REDSTONE;
            default:
                return null;
        }
    }

    // 更新欠款记录
    private void updateDebt(UUID uuid, Material type, int amount) {
        debtMap.computeIfAbsent(uuid, k -> new HashMap<>())
                .merge(type, amount, Integer::sum);
        // 立即更新计分板
        updateDebtScoreboard();
    }

    // 获取当前欠款数量
    private int getDebtCount(UUID uuid, Material type) {
        return debtMap.getOrDefault(uuid, Collections.emptyMap())
                .getOrDefault(type, 0);
    }

    // 获取物品中文名（需自行扩展）
    private String getChineseName(Material type) {
        switch (type) {
            case DIAMOND:
                return "钻石";
            case COAL:
                return "煤炭";
            case RAW_GOLD:
                return "粗金";
            case RAW_IRON:
                return "粗铁";
            case EMERALD:
                return "绿宝石";
            case LAPIS_LAZULI:
                return "青金石";
            case GOLD_NUGGET:
                return "金粒";
            case ANCIENT_DEBRIS:
                return "远古残骸";
            case REDSTONE:
                return "红石";
            case RAW_COPPER:
                return "粗铜";
            case QUARTZ:
                return "下界石英";
            default:
                return type.toString();
        }
    }
}
