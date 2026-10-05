package rummage;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("rummage.json");
	public static Config INSTANCE = new Config();

	public boolean enabled = true;
	public boolean autoClear = true;
	public int color = 0x00FF00;
	public float thickness = 2.5f;
	public boolean gamerMode = false;
	public float rgbSpeed = 1;

	int currentColor() {
		if (!gamerMode) return 0xFF000000 | color;
		float hue = (float) (System.currentTimeMillis() / 10_000.0 * rgbSpeed % 1.0);
		return Mth.hsvToArgb(hue, 1, 1, 255);
	}

	static void load() {
		try {
			if (Files.exists(FILE)) {
				Config loaded = new Gson().fromJson(Files.readString(FILE), Config.class);
				if (loaded != null) INSTANCE = loaded;
			}
		} catch (IOException | JsonParseException e) {
			Rummage.LOG.error("Couldn't read {}", FILE, e);
		}
	}

	static void save() {
		try {
			Files.writeString(FILE, new Gson().toJson(INSTANCE));
		} catch (IOException e) {
			Rummage.LOG.error("Couldn't write {}", FILE, e);
		}
	}
}
