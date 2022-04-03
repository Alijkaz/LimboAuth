/*
 * Copyright (C) 2021 - 2022 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboauth;

import com.google.inject.Inject;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.field.FieldType;
import com.j256.ormlite.jdbc.JdbcPooledConnectionSource;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.table.TableUtils;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.file.SchematicFile;
import net.elytrium.limboapi.api.file.WorldFile;
import net.elytrium.limboauth.command.ChangePasswordCommand;
import net.elytrium.limboauth.command.DestroySessionCommand;
import net.elytrium.limboauth.command.ForceChangePasswordCommand;
import net.elytrium.limboauth.command.ForceUnregisterCommand;
import net.elytrium.limboauth.command.LimboAuthCommand;
import net.elytrium.limboauth.command.PremiumCommand;
import net.elytrium.limboauth.command.TotpCommand;
import net.elytrium.limboauth.command.UnregisterCommand;
import net.elytrium.limboauth.event.AuthPluginReloadEvent;
import net.elytrium.limboauth.event.PreAuthorizationEvent;
import net.elytrium.limboauth.event.PreEvent;
import net.elytrium.limboauth.event.PreRegisterEvent;
import net.elytrium.limboauth.event.TaskEvent;
import net.elytrium.limboauth.floodgate.FloodgateApiHolder;
import net.elytrium.limboauth.handler.AuthSessionHandler;
import net.elytrium.limboauth.listener.AuthListener;
import net.elytrium.limboauth.model.RegisteredPlayer;
import net.elytrium.limboauth.utils.UpdatesChecker;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bstats.velocity.Metrics;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;

@Plugin(
    id = "limboauth",
    name = "LimboAuth",
    version = BuildConstants.AUTH_VERSION,
    url = "https://elytrium.net/",
    authors = {
        "hevav",
        "mdxd44"
    },
    dependencies = {
        @Dependency(id = "limboapi"),
        @Dependency(id = "floodgate", optional = true)
    }
)
public class LimboAuth {

  private final Map<String, CachedSessionUser> cachedAuthChecks = new ConcurrentHashMap<>();
  private final Map<String, CachedPremiumUser> premiumCache = new ConcurrentHashMap<>();
  private final Map<UUID, Runnable> postLoginTasks = new ConcurrentHashMap<>();
  private final Set<String> unsafePasswords = new HashSet<>();

  private final HttpClient client = HttpClient.newHttpClient();
  private final ProxyServer server;
  private final Logger logger;
  private final Metrics.Factory metricsFactory;
  private final Path dataDirectory;
  private final LimboFactory factory;
  private final FloodgateApiHolder floodgateApi;

  private JdbcPooledConnectionSource connectionSource;

  private Dao<RegisteredPlayer, String> playerDao;
  private Pattern nicknameValidationPattern;
  private Limbo authServer;

  @Inject
  public LimboAuth(ProxyServer server, Logger logger, Metrics.Factory metricsFactory, @DataDirectory Path dataDirectory) {
    this.server = server;
    this.logger = logger;
    this.metricsFactory = metricsFactory;
    this.dataDirectory = dataDirectory;

    this.factory = (LimboFactory) this.server.getPluginManager().getPlugin("limboapi").flatMap(PluginContainer::getInstance).orElseThrow();

    if (this.server.getPluginManager().getPlugin("floodgate").isPresent()) {
      this.floodgateApi = new FloodgateApiHolder();
    } else {
      this.floodgateApi = null;
    }
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) throws Exception {
    Metrics metrics = this.metricsFactory.make(this, 13700);
    System.setProperty("com.j256.simplelogging.level", "ERROR");

    this.reload();

    metrics.addCustomChart(new SimplePie("floodgate_auth", () -> String.valueOf(Settings.IMP.MAIN.FLOODGATE_NEED_AUTH)));
    metrics.addCustomChart(new SimplePie("premium_auth", () -> String.valueOf(Settings.IMP.MAIN.ONLINE_MODE_NEED_AUTH)));
    metrics.addCustomChart(new SimplePie("db_type", () -> Settings.IMP.DATABASE.STORAGE_TYPE));
    metrics.addCustomChart(new SimplePie("load_world", () -> String.valueOf(Settings.IMP.MAIN.LOAD_WORLD)));
    metrics.addCustomChart(new SimplePie("totp_enabled", () -> String.valueOf(Settings.IMP.MAIN.ENABLE_TOTP)));
    metrics.addCustomChart(new SimplePie("dimension", () -> Settings.IMP.MAIN.DIMENSION));
    metrics.addCustomChart(new SimplePie("save_uuid", () -> String.valueOf(Settings.IMP.MAIN.SAVE_UUID)));
    metrics.addCustomChart(new SingleLineChart("registered_players", () -> Math.toIntExact(this.playerDao.countOf())));

    UpdatesChecker.checkForUpdates(this.getLogger());
  }

  @SuppressWarnings("SwitchStatementWithTooFewBranches")
  public void reload() throws Exception {
    Settings.IMP.reload(new File(this.dataDirectory.toFile().getAbsoluteFile(), "config.yml"));

    if (this.floodgateApi == null && !Settings.IMP.MAIN.FLOODGATE_NEED_AUTH) {
      throw new IllegalStateException("If you don't need to auth floodgate players please install floodgate plugin.");
    }

    if (Settings.IMP.MAIN.CHECK_PASSWORD_STRENGTH) {
      this.unsafePasswords.clear();
      Path unsafePasswordsPath = Paths.get(this.dataDirectory.toFile().getAbsolutePath(), Settings.IMP.MAIN.UNSAFE_PASSWORDS_FILE);
      if (!unsafePasswordsPath.toFile().exists()) {
        Files.copy(Objects.requireNonNull(this.getClass().getResourceAsStream("/unsafe_passwords.txt")), unsafePasswordsPath);
      }

      this.unsafePasswords.addAll(Files.lines(unsafePasswordsPath).collect(Collectors.toSet()));
    }

    this.cachedAuthChecks.clear();

    Settings.DATABASE dbConfig = Settings.IMP.DATABASE;
    // requireNonNull prevents the shade plugin from excluding the drivers in minimized jar.
    switch (dbConfig.STORAGE_TYPE.toLowerCase(Locale.ROOT)) {
      case "h2": {
        Objects.requireNonNull(org.h2.Driver.class);
        Objects.requireNonNull(org.h2.engine.Engine.class);
        this.connectionSource = new JdbcPooledConnectionSource("jdbc:h2:" + this.dataDirectory.toFile().getAbsoluteFile() + "/limboauth");
        break;
      }
      case "mysql": {
        Objects.requireNonNull(com.mysql.cj.jdbc.Driver.class);
        Objects.requireNonNull(com.mysql.cj.conf.url.SingleConnectionUrl.class);
        this.connectionSource = new JdbcPooledConnectionSource(
            "jdbc:mysql://" + dbConfig.HOSTNAME + "/" + dbConfig.DATABASE + dbConfig.CONNECTION_PARAMETERS, dbConfig.USER, dbConfig.PASSWORD
        );
        break;
      }
      case "postgresql": {
        Objects.requireNonNull(org.postgresql.Driver.class);
        this.connectionSource = new JdbcPooledConnectionSource(
            "jdbc:postgresql://" + dbConfig.HOSTNAME + "/" + dbConfig.DATABASE + dbConfig.CONNECTION_PARAMETERS, dbConfig.USER, dbConfig.PASSWORD
        );
        break;
      }
      default: {
        this.getLogger().error("WRONG DATABASE TYPE.");
        this.server.shutdown();
        return;
      }
    }

    TableUtils.createTableIfNotExists(this.connectionSource, RegisteredPlayer.class);
    this.playerDao = DaoManager.createDao(this.connectionSource, RegisteredPlayer.class);
    this.nicknameValidationPattern = Pattern.compile(Settings.IMP.MAIN.ALLOWED_NICKNAME_REGEX);

    this.migrateDb(this.playerDao);

    CommandManager manager = this.server.getCommandManager();
    manager.unregister("unregister");
    manager.unregister("premium");
    manager.unregister("forceunregister");
    manager.unregister("changepassword");
    manager.unregister("forcechangepassword");
    manager.unregister("destroysession");
    manager.unregister("2fa");
    manager.unregister("limboauth");

    manager.register("unregister", new UnregisterCommand(this, this.playerDao), "unreg");
    manager.register("premium", new PremiumCommand(this, this.playerDao), "license");
    manager.register("forceunregister", new ForceUnregisterCommand(this, this.server, this.playerDao), "forceunreg");
    manager.register("changepassword", new ChangePasswordCommand(this.playerDao), "changepass");
    manager.register("forcechangepassword", new ForceChangePasswordCommand(this.server, this.playerDao), "forcechangepass");
    manager.register("destroysession", new DestroySessionCommand(this));
    if (Settings.IMP.MAIN.ENABLE_TOTP) {
      manager.register("2fa", new TotpCommand(this.playerDao), "totp");
    }
    manager.register("limboauth", new LimboAuthCommand(this), "la", "auth", "lauth");

    Settings.MAIN.AUTH_COORDS authCoords = Settings.IMP.MAIN.AUTH_COORDS;
    VirtualWorld authWorld = this.factory.createVirtualWorld(
        Dimension.valueOf(Settings.IMP.MAIN.DIMENSION),
        authCoords.X, authCoords.Y, authCoords.Z,
        (float) authCoords.YAW, (float) authCoords.PITCH
    );

    if (Settings.IMP.MAIN.LOAD_WORLD) {
      try {
        Path path = this.dataDirectory.resolve(Settings.IMP.MAIN.WORLD_FILE_PATH);
        WorldFile file;
        switch (Settings.IMP.MAIN.WORLD_FILE_TYPE) {
          case "schematic": {
            file = new SchematicFile(path);
            break;
          }
          default: {
            this.getLogger().error("Incorrect world file type.");
            this.server.shutdown();
            return;
          }
        }

        Settings.MAIN.WORLD_COORDS coords = Settings.IMP.MAIN.WORLD_COORDS;
        file.toWorld(this.factory, authWorld, coords.X, coords.Y, coords.Z);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    this.authServer = this.factory
        .createLimbo(authWorld)
        .setName("LimboAuth")
        .registerCommand(new AuthCommandMeta(this, this.filterCommands(Settings.IMP.MAIN.REGISTER_COMMAND)), new AuthCommand())
        .registerCommand(new AuthCommandMeta(this, this.filterCommands(Settings.IMP.MAIN.LOGIN_COMMAND)), new AuthCommand())
        .registerCommand(new AuthCommandMeta(this, this.filterCommands(Settings.IMP.MAIN.TOTP_COMMAND)), new AuthCommand());

    this.server.getEventManager().unregisterListeners(this);
    this.server.getEventManager().register(this, new AuthListener(this, this.playerDao, this.floodgateApi));

    Executors.newScheduledThreadPool(1, task -> new Thread(task, "purge-cache")).scheduleAtFixedRate(() ->
            this.checkCache(this.cachedAuthChecks, Settings.IMP.MAIN.PURGE_CACHE_MILLIS),
        Settings.IMP.MAIN.PURGE_CACHE_MILLIS,
        Settings.IMP.MAIN.PURGE_CACHE_MILLIS,
        TimeUnit.MILLISECONDS
    );

    Executors.newScheduledThreadPool(1, task -> new Thread(task, "purge-premium-cache")).scheduleAtFixedRate(() ->
            this.checkCache(this.premiumCache, Settings.IMP.MAIN.PURGE_PREMIUM_CACHE_MILLIS),
        Settings.IMP.MAIN.PURGE_PREMIUM_CACHE_MILLIS,
        Settings.IMP.MAIN.PURGE_PREMIUM_CACHE_MILLIS,
        TimeUnit.MILLISECONDS
    );

    this.server.getEventManager().fireAndForget(new AuthPluginReloadEvent());
  }

  private List<String> filterCommands(List<String> commands) {
    return commands.stream().filter(e -> e.startsWith("/")).map(e -> e.substring(1)).collect(Collectors.toList());
  }

  public void migrateDb(Dao<?, ?> dao) {
    Set<FieldType> tables = new HashSet<>();
    Collections.addAll(tables, dao.getTableInfo().getFieldTypes());

    String findSql;
    switch (Settings.IMP.DATABASE.STORAGE_TYPE) {
      case "h2": {
        findSql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '"
            + dao.getTableInfo().getTableName() + "';";
        break;
      }
      case "postgresql": {
        findSql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_CATALOG = '" + Settings.IMP.DATABASE.DATABASE
            + "' AND TABLE_NAME = '" + dao.getTableInfo().getTableName() + "';";
        break;
      }
      case "mysql": {
        findSql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '" + Settings.IMP.DATABASE.DATABASE
            + "' AND TABLE_NAME = '" + dao.getTableInfo().getTableName() + "';";
        break;
      }
      default: {
        this.getLogger().error("WRONG DATABASE TYPE.");
        this.server.shutdown();
        return;
      }
    }

    try {
      dao.queryRaw(findSql).forEach(e -> tables.removeIf(q -> q.getColumnName().equalsIgnoreCase(e[0])));

      tables.forEach(t -> {
        try {
          String columnDefinition = t.getColumnDefinition();
          StringBuilder builder = new StringBuilder("ALTER TABLE \"" + dao.getTableInfo().getTableName() + "\" ADD ");
          List<String> dummy = new ArrayList<>();
          if (columnDefinition == null) {
            dao.getConnectionSource().getDatabaseType().appendColumnArg(t.getTableName(), builder, t, dummy, dummy, dummy, dummy);
          } else {
            dao.getConnectionSource().getDatabaseType().appendEscapedEntityName(builder, t.getColumnName());
            builder.append(" ").append(columnDefinition).append(" ");
          }

          dao.executeRawNoArgs(builder.toString());
        } catch (SQLException e) {
          e.printStackTrace();
        }
      });
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  public void cacheAuthUser(Player player) {
    String username = player.getUsername();
    String lowercaseUsername = username.toLowerCase(Locale.ROOT);
    this.cachedAuthChecks.remove(lowercaseUsername);
    this.cachedAuthChecks.put(lowercaseUsername, new CachedSessionUser(player.getRemoteAddress().getAddress(), username, System.currentTimeMillis()));
  }

  public void removePlayerFromCache(String username) {
    this.cachedAuthChecks.remove(username.toLowerCase(Locale.ROOT));
  }

  public boolean needAuth(Player player) {
    String username = player.getUsername().toLowerCase(Locale.ROOT);
    if (!this.cachedAuthChecks.containsKey(username)) {
      return true;
    } else {
      CachedSessionUser sessionUser = this.cachedAuthChecks.get(username);
      return !sessionUser.getInetAddress().equals(player.getRemoteAddress().getAddress()) || !sessionUser.getUsername().equals(username);
    }
  }

  public void authPlayer(Player player) {
    String nickname = player.getUsername();
    boolean isFloodgate = !Settings.IMP.MAIN.FLOODGATE_NEED_AUTH && this.floodgateApi.isFloodgatePlayer(player.getUniqueId());

    String validatorNickname = (isFloodgate) ? nickname.substring(this.floodgateApi.getPrefixLength()) : nickname;

    if (!this.nicknameValidationPattern.matcher(validatorNickname).matches()) {
      player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(Settings.IMP.MAIN.STRINGS.NICKNAME_INVALID_KICK));
      return;
    }

    RegisteredPlayer registeredPlayer = AuthSessionHandler.fetchInfo(this.playerDao, nickname);
    boolean onlineMode = player.isOnlineMode();
    TaskEvent.Result result = TaskEvent.Result.NORMAL;

    if (onlineMode || isFloodgate) {
      if (registeredPlayer == null || registeredPlayer.getHash().isEmpty()) {
        registeredPlayer = AuthSessionHandler.fetchInfo(this.playerDao, player.getUniqueId());
        if (registeredPlayer == null || registeredPlayer.getHash().isEmpty()) {
          // Due to the current connection state, which is set to LOGIN there, we cannot send the packets.
          // We need to wait for the PLAY connection state to set.
          this.postLoginTasks.put(player.getUniqueId(), () -> {
            if (onlineMode) {
              if (!Settings.IMP.MAIN.STRINGS.LOGIN_PREMIUM.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_PREMIUM));
              }
              if (!Settings.IMP.MAIN.STRINGS.LOGIN_PREMIUM_TITLE.isEmpty() && !Settings.IMP.MAIN.STRINGS.LOGIN_PREMIUM_SUBTITLE.isEmpty()) {
                player.showTitle(
                    Title.title(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_PREMIUM_TITLE),
                        LegacyComponentSerializer.legacyAmpersand().deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_PREMIUM_SUBTITLE),
                        Settings.IMP.MAIN.PREMIUM_TITLE_SETTINGS.toTimes()
                    )
                );
              }
            } else {
              if (!Settings.IMP.MAIN.STRINGS.LOGIN_FLOODGATE.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_FLOODGATE));
              }
              if (!Settings.IMP.MAIN.STRINGS.LOGIN_FLOODGATE_TITLE.isEmpty() && !Settings.IMP.MAIN.STRINGS.LOGIN_FLOODGATE_SUBTITLE.isEmpty()) {
                player.showTitle(
                    Title.title(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_FLOODGATE_TITLE),
                        LegacyComponentSerializer.legacyAmpersand().deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_FLOODGATE_SUBTITLE),
                        Settings.IMP.MAIN.PREMIUM_TITLE_SETTINGS.toTimes()
                    )
                );
              }
            }
          });

          result = TaskEvent.Result.BYPASS;
        }
      }
    }

    if (registeredPlayer == null) {
      Consumer<TaskEvent> eventConsumer = (event) -> this.sendPlayer(event, null);
      this.server.getEventManager().fire(new PreRegisterEvent(result, player, eventConsumer)).thenAcceptAsync(eventConsumer);
    } else {
      Consumer<TaskEvent> eventConsumer = (event) -> this.sendPlayer(event, ((PreAuthorizationEvent) event).getPlayerInfo());
      this.server.getEventManager().fire(new PreAuthorizationEvent(result, player, registeredPlayer, eventConsumer)).thenAcceptAsync(eventConsumer);
    }
  }

  private void sendPlayer(TaskEvent event, RegisteredPlayer registeredPlayer) {
    Player player = ((PreEvent) event).getPlayer();

    switch (event.getResult()) {
      case BYPASS: {
        this.factory.passLoginLimbo(player);
        break;
      }
      case CANCEL: {
        player.disconnect(event.getReason());
        break;
      }
      case WAIT: {
        return;
      }
      case NORMAL:
      default: {
        try {
          this.authServer.spawnPlayer(player, new AuthSessionHandler(this.playerDao, player, this, registeredPlayer));
        } catch (Throwable t) {
          this.getLogger().error("Error", t);
        }

        break;
      }
    }
  }

  public boolean isPremiumExternal(String nickname) {
    String lowercaseNickname = nickname.toLowerCase(Locale.ROOT);
    if (this.premiumCache.containsKey(lowercaseNickname)) {
      return this.premiumCache.get(lowercaseNickname).isPremium();
    }

    try {
      int statusCode = this.client.send(
          HttpRequest.newBuilder()
              .uri(URI.create(
                  String.format(
                      Settings.IMP.MAIN.ISPREMIUM_AUTH_URL,
                      URLEncoder.encode(lowercaseNickname, StandardCharsets.UTF_8))))
              .build(),
          HttpResponse.BodyHandlers.ofString()
      ).statusCode();

      boolean isPremium = statusCode == 200;

      // 429 Too Many Requests
      if (statusCode != 429) {
        this.premiumCache.put(lowercaseNickname, new CachedPremiumUser(isPremium, System.currentTimeMillis()));
      } else {
        return Settings.IMP.MAIN.ON_RATE_LIMIT_PREMIUM;
      }

      return isPremium;
    } catch (IOException | InterruptedException e) {
      this.getLogger().error("Unable to authenticate with Mojang", e);
      return Settings.IMP.MAIN.ON_RATE_LIMIT_PREMIUM;
    }
  }

  public boolean isPremium(String nickname) {
    if (Settings.IMP.MAIN.FORCE_OFFLINE_MODE) {
      return false;
    }

    try {
      if (this.isPremiumExternal(nickname)) {
        QueryBuilder<RegisteredPlayer, String> premiumRegisteredQuery = this.playerDao.queryBuilder();
        premiumRegisteredQuery.where()
            .eq("LOWERCASENICKNAME", nickname.toLowerCase(Locale.ROOT))
            .and()
            .ne("HASH", "");
        premiumRegisteredQuery.setCountOf(true);

        QueryBuilder<RegisteredPlayer, String> premiumUnregisteredQuery = this.playerDao.queryBuilder();
        premiumUnregisteredQuery.where()
            .eq("LOWERCASENICKNAME", nickname.toLowerCase(Locale.ROOT))
            .and()
            .eq("HASH", "");
        premiumUnregisteredQuery.setCountOf(true);

        if (Settings.IMP.MAIN.ONLINE_MODE_NEED_AUTH) {
          return this.playerDao.countOf(premiumRegisteredQuery.prepare()) == 0 && this.playerDao.countOf(premiumUnregisteredQuery.prepare()) != 0;
        } else {
          return this.playerDao.countOf(premiumRegisteredQuery.prepare()) == 0;
        }
      } else {
        return false;
      }
    } catch (SQLException e) {
      this.getLogger().error("Unable to authenticate with Mojang", e);
      return true;
    }
  }

  private void checkCache(Map<String, ? extends CachedUser> userMap, long time) {
    userMap.entrySet().stream()
        .filter(u -> u.getValue().getCheckTime() + time <= System.currentTimeMillis())
        .map(Map.Entry::getKey)
        .forEach(userMap::remove);
  }

  public JdbcPooledConnectionSource getConnectionSource() {
    return this.connectionSource;
  }

  public Dao<RegisteredPlayer, String> getPlayerDao() {
    return this.playerDao;
  }

  public Set<String> getUnsafePasswords() {
    return this.unsafePasswords;
  }

  public Map<UUID, Runnable> getPostLoginTasks() {
    return this.postLoginTasks;
  }

  public Logger getLogger() {
    return this.logger;
  }

  public ProxyServer getServer() {
    return this.server;
  }

  private static class CachedUser {

    private final long checkTime;

    public CachedUser(long checkTime) {
      this.checkTime = checkTime;
    }

    public long getCheckTime() {
      return this.checkTime;
    }
  }

  private static class CachedSessionUser extends CachedUser {

    private final InetAddress inetAddress;
    private final String username;

    public CachedSessionUser(InetAddress inetAddress, String username, long checkTime) {
      super(checkTime);
      this.inetAddress = inetAddress;
      this.username = username;
    }

    public InetAddress getInetAddress() {
      return this.inetAddress;
    }

    public String getUsername() {
      return this.username;
    }
  }

  private static class CachedPremiumUser extends CachedUser {

    private final boolean isPremium;

    public CachedPremiumUser(boolean isPremium, long checkTime) {
      super(checkTime);
      this.isPremium = isPremium;
    }

    public boolean isPremium() {
      return this.isPremium;
    }
  }

  private static class AuthCommandMeta implements CommandMeta {

    private final LimboAuth plugin;
    private final Collection<String> aliases;

    AuthCommandMeta(LimboAuth plugin, Collection<String> aliases) {
      this.plugin = plugin;
      this.aliases = aliases;
    }

    @Override
    public Collection<String> getAliases() {
      return this.aliases;
    }

    @Override
    public Collection<CommandNode<CommandSource>> getHints() {
      return Collections.emptyList();
    }

    @Override
    public @Nullable Object getPlugin() {
      return this.plugin;
    }
  }

  private static class AuthCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {

    }
  }
}
