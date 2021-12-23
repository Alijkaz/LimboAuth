/*
 * Copyright (C) 2021 Elytrium
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

package net.elytrium.limboauth.handler;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.j256.ormlite.dao.Dao;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.Settings;
import net.elytrium.limboauth.migration.MigrationHash;
import net.elytrium.limboauth.model.RegisteredPlayer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

public class AuthSessionHandler implements LimboSessionHandler {

  private static final CodeVerifier verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());

  private final Dao<RegisteredPlayer, String> playerDao;
  private final Player proxyPlayer;
  private final RegisteredPlayer playerInfo;
  private final LimboAuth plugin;

  private final long joinTime = System.currentTimeMillis();
  private final BossBar bossBar = BossBar.bossBar(
      Component.empty(),
      1,
      BossBar.Color.valueOf(Settings.IMP.MAIN.BOSSBAR_COLOR.toUpperCase(Locale.ROOT)),
      BossBar.Overlay.valueOf(Settings.IMP.MAIN.BOSSBAR_OVERLAY.toUpperCase(Locale.ROOT))
  );
  private ScheduledTask authMainTask;

  private LimboPlayer player;
  private String ip;
  private int attempts = Settings.IMP.MAIN.LOGIN_ATTEMPTS;
  private boolean totp = false;

  public AuthSessionHandler(Dao<RegisteredPlayer, String> playerDao, Player proxyPlayer, LimboAuth plugin, String lowercaseNickname) {
    this.playerDao = playerDao;
    this.proxyPlayer = proxyPlayer;
    this.playerInfo = this.fetchInfo(lowercaseNickname);
    this.plugin = plugin;
  }

  @Override
  public void onSpawn(Limbo server, LimboPlayer player) {
    this.player = player;
    this.player.disableFalling();
    this.ip = this.proxyPlayer.getRemoteAddress().getAddress().getHostAddress();

    if (this.playerInfo == null) {
      this.checkIp();
    } else {
      this.checkCase();
    }

    boolean bossBarEnabled = Settings.IMP.MAIN.ENABLE_BOSSBAR;
    float bossBarMultiplier = 1000F / Settings.IMP.MAIN.AUTH_TIME;
    if (bossBarEnabled) {
      this.proxyPlayer.showBossBar(this.bossBar);
    }
    this.authMainTask = this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      if (System.currentTimeMillis() - this.joinTime > Settings.IMP.MAIN.AUTH_TIME) {
        this.proxyPlayer.disconnect(this.deserialize(Settings.IMP.MAIN.STRINGS.TIMES_UP));
        return;
      }
      if (bossBarEnabled) {
        long timeSinceJoin = Settings.IMP.MAIN.AUTH_TIME - (System.currentTimeMillis() - AuthSessionHandler.this.joinTime);
        this.bossBar.name(this.deserialize(MessageFormat.format(Settings.IMP.MAIN.STRINGS.BOSSBAR, (int) (timeSinceJoin / 1000))));
        this.bossBar.progress((timeSinceJoin * bossBarMultiplier) / 1000);
      }
    }).repeat(1, TimeUnit.SECONDS).schedule();

    this.sendMessage(true);
  }

  @Override
  public void onChat(String message) {
    String[] args = message.split(" ");
    if (args.length != 0 && this.checkArgsLength(args.length)) {
      switch (args[0]) {
        case "/reg":
        case "/register":
        case "/r": {
          if (!this.totp && this.playerInfo == null) {
            if (this.checkPasswordsRepeat(args) && this.checkPasswordLength(args[1]) && this.checkPasswordStrength(args[1])) {
              this.register(args[1]);
              this.proxyPlayer.sendMessage(this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_SUCCESSFUL));
              if (!Settings.IMP.MAIN.STRINGS.REGISTER_SUCCESSFUL_TITLE.isEmpty() && !Settings.IMP.MAIN.STRINGS.REGISTER_SUCCESSFUL_SUBTITLE.isEmpty()) {
                this.proxyPlayer.showTitle(
                    Title.title(
                        this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_SUCCESSFUL_TITLE),
                        this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_SUCCESSFUL_SUBTITLE)
                    )
                );
              }
              this.finishAuth();
            }
          } else {
            this.sendMessage(false);
          }
          break;
        }
        case "/log":
        case "/login":
        case "/l": {
          if (!this.totp && this.playerInfo != null) {
            if (this.checkPassword(args[1])) {
              this.loginOrTotp();
            } else if (--this.attempts != 0) {
              this.proxyPlayer.sendMessage(this.deserialize(MessageFormat.format(Settings.IMP.MAIN.STRINGS.LOGIN_WRONG_PASSWORD, this.attempts)));
            } else {
              this.proxyPlayer.disconnect(this.deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_WRONG_PASSWORD_KICK));
            }
          } else {
            this.sendMessage(false);
          }
          break;
        }
        case "/totp":
        case "/2fa": {
          if (this.totp) {
            if (verifier.isValidCode(this.playerInfo.getTotpToken(), args[1])) {
              this.finishLogin();
            } else {
              this.sendMessage(false);
            }
          } else {
            this.sendMessage(false);
          }
          break;
        }
        default: {
          this.sendMessage(false);
        }
      }
    } else {
      this.sendMessage(false);
    }
  }

  @Override
  public void onDisconnect() {
    if (this.authMainTask != null) {
      this.authMainTask.cancel();
    }

    this.proxyPlayer.hideBossBar(this.bossBar);
  }

  public static RegisteredPlayer fetchInfo(Dao<RegisteredPlayer, String> playerDao, String nickname) {
    List<RegisteredPlayer> playerList = null;
    try {
      playerList = playerDao.queryForEq("LOWERCASENICKNAME", nickname.toLowerCase(Locale.ROOT));
    } catch (SQLException e) {
      e.printStackTrace();
    }

    return (playerList != null ? playerList.size() : 0) == 0 ? null : playerList.get(0);
  }

  public static RegisteredPlayer fetchInfo(Dao<RegisteredPlayer, String> playerDao, UUID uuid) {
    List<RegisteredPlayer> playerList = null;
    try {
      playerList = playerDao.queryForEq("PREMIUMUUID", uuid.toString());
    } catch (SQLException e) {
      e.printStackTrace();
    }

    return (playerList != null ? playerList.size() : 0) == 0 ? null : playerList.get(0);
  }

  private RegisteredPlayer fetchInfo(String nickname) {
    return fetchInfo(this.playerDao, nickname);
  }

  public static CodeVerifier getVerifier() {
    return verifier;
  }

  private boolean checkPassword(String password) {
    return checkPassword(password, this.playerInfo, this.playerDao);
  }

  public static boolean checkPassword(String password, RegisteredPlayer player, Dao<RegisteredPlayer, String> playerDao) {
    boolean isCorrect = BCrypt.verifyer().verify(password.getBytes(StandardCharsets.UTF_8), player.getHash().getBytes(StandardCharsets.UTF_8)).verified;

    if (!isCorrect && !Settings.IMP.MAIN.MIGRATION_HASH.isEmpty()) {
      isCorrect = MigrationHash.valueOf(Settings.IMP.MAIN.MIGRATION_HASH).checkPassword(player.getHash(), password);

      if (isCorrect) {
        player.setHash(genHash(password));
        try {
          playerDao.update(player);
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }
    }

    return isCorrect;
  }

  private void checkIp() {
    try {
      List<RegisteredPlayer> alreadyRegistered = this.playerDao.queryForEq("IP", this.ip);

      if (alreadyRegistered == null) {
        return;
      }

      AtomicInteger sizeOfValid = new AtomicInteger(alreadyRegistered.size());

      if (Settings.IMP.MAIN.IP_LIMIT_VALID_TIME != 0) {
        long checkDate = System.currentTimeMillis() - Settings.IMP.MAIN.IP_LIMIT_VALID_TIME;

        alreadyRegistered.stream()
            .filter(e -> e.getRegDate() < checkDate)
            .forEach(e -> {
              try {
                e.setIP("");
                this.playerDao.update(e);
                sizeOfValid.decrementAndGet();
              } catch (SQLException ex) {
                ex.printStackTrace();
              }
            });
      }

      if (sizeOfValid.get() >= Settings.IMP.MAIN.IP_LIMIT_REGISTRATIONS) {
        this.proxyPlayer.disconnect(this.deserialize(Settings.IMP.MAIN.STRINGS.IP_LIMIT));
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  private void checkCase() {
    if (!this.proxyPlayer.getUsername().equals(this.playerInfo.getNickname())) {
      this.proxyPlayer.disconnect(this.deserialize(Settings.IMP.MAIN.STRINGS.WRONG_NICKNAME_CASE_KICK));
    }
  }

  private void register(String password) {
    RegisteredPlayer registeredPlayer = new RegisteredPlayer(
        this.proxyPlayer.getUsername(),
        this.proxyPlayer.getUsername().toLowerCase(Locale.ROOT),
        genHash(password),
        this.ip,
        "",
        System.currentTimeMillis(),
        this.proxyPlayer.getUniqueId().toString(),
        ""
    );

    try {
      this.playerDao.create(registeredPlayer);
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  private void loginOrTotp() {
    if (this.playerInfo.getTotpToken().isEmpty()) {
      this.finishLogin();
    } else {
      this.totp = true;
      this.sendMessage(true);
    }
  }

  private void finishLogin() {
    this.proxyPlayer.sendMessage(this.deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_SUCCESSFUL));
    if (!Settings.IMP.MAIN.STRINGS.LOGIN_SUCCESSFUL_TITLE.isEmpty() && !Settings.IMP.MAIN.STRINGS.LOGIN_SUCCESSFUL_SUBTITLE.isEmpty()) {
      this.proxyPlayer.showTitle(
          Title.title(
              this.deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_SUCCESSFUL_TITLE),
              this.deserialize(Settings.IMP.MAIN.STRINGS.LOGIN_SUCCESSFUL_SUBTITLE)
          )
      );
    }
    this.finishAuth();
  }

  private void finishAuth() {
    this.plugin.cacheAuthUser(this.proxyPlayer);
    this.player.disconnect();
  }

  private void sendMessage(boolean sendTitle) {
    if (this.totp) {
      this.proxyPlayer.sendMessage(this.deserialize(Settings.IMP.MAIN.STRINGS.TOTP));
      if (sendTitle && !Settings.IMP.MAIN.STRINGS.TOTP_TITLE.isEmpty() && !Settings.IMP.MAIN.STRINGS.TOTP_SUBTITLE.isEmpty()) {
        this.proxyPlayer.showTitle(
            Title.title(this.deserialize(Settings.IMP.MAIN.STRINGS.TOTP_TITLE), this.deserialize(Settings.IMP.MAIN.STRINGS.TOTP_SUBTITLE))
        );
      }
    } else if (this.playerInfo == null) {
      this.proxyPlayer.sendMessage(this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER));
      if (sendTitle && !Settings.IMP.MAIN.STRINGS.REGISTER_TITLE.isEmpty() && !Settings.IMP.MAIN.STRINGS.REGISTER_SUBTITLE.isEmpty()) {
        this.proxyPlayer.showTitle(
            Title.title(this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_TITLE), this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_SUBTITLE))
        );
      }
    } else {
      this.proxyPlayer.sendMessage(this.deserialize(MessageFormat.format(Settings.IMP.MAIN.STRINGS.LOGIN, this.attempts)));
      if (sendTitle && !Settings.IMP.MAIN.STRINGS.LOGIN_TITLE.isEmpty() && !Settings.IMP.MAIN.STRINGS.LOGIN_SUBTITLE.isEmpty()) {
        this.proxyPlayer.showTitle(
            Title.title(
                this.deserialize(MessageFormat.format(Settings.IMP.MAIN.STRINGS.LOGIN_TITLE, this.attempts)),
                this.deserialize(MessageFormat.format(Settings.IMP.MAIN.STRINGS.LOGIN_SUBTITLE, this.attempts))
            )
        );
      }
    }
  }

  private boolean checkPasswordLength(String password) {
    int length = password.length();
    if (length > Settings.IMP.MAIN.MAX_PASSWORD_LENGTH) {
      this.proxyPlayer.sendMessage(this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_PASSWORD_TOO_LONG));
      return false;
    } else if (length < Settings.IMP.MAIN.MIN_PASSWORD_LENGTH) {
      this.proxyPlayer.sendMessage(this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_PASSWORD_TOO_SHORT));
      return false;
    }

    return true;
  }

  private boolean checkPasswordStrength(String password) {
    if (Settings.IMP.MAIN.CHECK_PASSWORD_STRENGTH && this.plugin.getUnsafePasswords().contains(password)) {
      this.proxyPlayer.sendMessage(this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_PASSWORD_UNSAFE));
      return false;
    }

    return true;
  }

  private boolean checkPasswordsRepeat(String[] args) {
    if (Settings.IMP.MAIN.REGISTER_NEED_REPEAT_PASSWORD && !args[1].equals(args[2])) {
      this.proxyPlayer.sendMessage(this.deserialize(Settings.IMP.MAIN.STRINGS.REGISTER_DIFFERENT_PASSWORDS));
      return false;
    }

    return true;
  }

  private boolean checkArgsLength(int argsLength) {
    if (this.playerInfo == null && Settings.IMP.MAIN.REGISTER_NEED_REPEAT_PASSWORD) {
      return argsLength == 3;
    } else {
      return argsLength == 2;
    }
  }

  private Component deserialize(String text) {
    return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
  }

  public static String genHash(String password) {
    return BCrypt.withDefaults().hashToString(Settings.IMP.MAIN.BCRYPT_COST, password.toCharArray());
  }
}
