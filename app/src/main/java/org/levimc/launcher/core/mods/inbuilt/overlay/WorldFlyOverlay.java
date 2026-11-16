package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.widget.ImageButton;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.minecraft.MinecraftActivity;
import org.levimc.launcher.core.mods.inbuilt.model.ModIds;

public final class WorldFlyOverlay extends BaseOverlayButton {
    private boolean flying;
    private boolean commandPending;
    private boolean disableRequested;

    public WorldFlyOverlay(Activity activity) {
        super(activity);
    }

    @Override
    protected String getModId() {
        return ModIds.WORLD_FLY;
    }

    @Override
    protected int getIconResource() {
        return flying ? R.drawable.ic_world_fly_active : R.drawable.ic_world_fly;
    }

    @Override
    protected void onButtonClick() {
        setFlying(!flying);
    }

    public void disableFlight() {
        if (commandPending) {
            disableRequested = true;
        } else if (flying) {
            setFlying(false);
        }
    }

    private void setFlying(boolean enabled) {
        if (commandPending) return;
        if (!(activity instanceof MinecraftActivity)) {
            showUnavailable();
            return;
        }

        commandPending = true;
        ((MinecraftActivity) activity).requestWorldFlight(enabled, submitted -> {
            commandPending = false;
            if (!submitted) {
                disableRequested = false;
                showUnavailable();
                return;
            }
            flying = enabled;
            refreshIcon();
            if (disableRequested) {
                disableRequested = false;
                if (flying) setFlying(false);
            }
        });
    }

    private void showUnavailable() {
        Toast.makeText(activity, R.string.inbuilt_mod_world_fly_unavailable, Toast.LENGTH_LONG).show();
    }

    private void refreshIcon() {
        if (overlayView instanceof ImageButton) {
            ((ImageButton) overlayView).setImageResource(getIconResource());
        }
    }
}
