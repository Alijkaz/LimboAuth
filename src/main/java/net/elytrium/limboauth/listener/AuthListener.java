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

package net.elytrium.limboauth.listener;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.UpdateBuilder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.util.UuidUtils;
import java.sql.SQLException;
import java.util.UUID;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.event.SafeGameProfileRequestEvent;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.Settings;
import net.elytrium.limboauth.handler.AuthSessionHandler;
import net.elytrium.limboauth.model.RegisteredPlayer;

// TODO: Customizable events priority
public class AuthListener {

  private final Dao<RegisteredPlayer, String> playerDao;

  public AuthListener(Dao<RegisteredPlayer, String> playerDao) {
    this.playerDao = playerDao;
  }

  @Subscribe
  public void onProxyConnect(PreLoginEvent event) {
    if (!event.getResult().isForceOfflineMode()) {
      if (Settings.IMP.MAIN.ONLINE_MODE_NEED_AUTH || !LimboAuth.getInstance().isPremium(event.getUsername())) {
        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
      } else {
        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
      }
    }
  }

  @Subscribe
  public void onLogin(LoginLimboRegisterEvent event) {
    if (LimboAuth.getInstance().needAuth(event.getPlayer())) {
      event.addCallback(() -> LimboAuth.getInstance().authPlayer(event.getPlayer()));
    }
  }

  @Subscribe
  public void onProfile(SafeGameProfileRequestEvent event) {
    if (Settings.IMP.MAIN.SAVE_UUID) {
      RegisteredPlayer registeredPlayer = AuthSessionHandler.fetchInfo(this.playerDao, event.getOriginalProfile().getId());

      if (registeredPlayer != null) {
        event.setGameProfile(event.getOriginalProfile().withId(UUID.fromString(registeredPlayer.getUuid())));
        return;
      }

      registeredPlayer = AuthSessionHandler.fetchInfo(this.playerDao, event.getUsername());

      if (registeredPlayer != null) {
        String currentUuid = registeredPlayer.getUuid();

        if (event.isOnlineMode()) {
          try {
            registeredPlayer.setPremiumUuid(event.getOriginalProfile().getId().toString());
            registeredPlayer.setHash("");

            if (currentUuid.isEmpty()) {
              registeredPlayer.setUuid(UuidUtils.generateOfflinePlayerUuid(event.getUsername()).toString());
            }

            this.playerDao.update(registeredPlayer);
          } catch (SQLException e) {
            e.printStackTrace();
          }

          event.setGameProfile(event.getOriginalProfile().withId(UUID.fromString(currentUuid)));
        } else if (currentUuid.isEmpty()) {
          try {
            registeredPlayer.setUuid(event.getGameProfile().getId().toString());
            this.playerDao.update(registeredPlayer);
          } catch (SQLException ex) {
            ex.printStackTrace();
          }
        }
      }
    } else if (event.isOnlineMode()) {
      try {
        UpdateBuilder<RegisteredPlayer, String> updateBuilder = this.playerDao.updateBuilder();
        updateBuilder.where().eq("nickname", event.getUsername());
        updateBuilder.updateColumnValue("hash", "");
        updateBuilder.update();
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }

    if (!Settings.IMP.MAIN.FORCE_OFFLINE_UUID) {
      event.setGameProfile(event.getOriginalProfile().withId(UuidUtils.generateOfflinePlayerUuid(event.getUsername())));
    }
  }
}
