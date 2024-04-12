/*
 * Copyright (c) 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.meta.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.http.InternalServerErrorResponse;
import net.fabricmc.meta.FabricMeta;
import org.apache.commons.io.IOUtils;

import net.fabricmc.meta.utils.LoaderMeta;
import net.fabricmc.meta.web.models.LoaderInfoV2;

public class ProfileHandler {

	private static final Executor EXECUTOR = Executors.newFixedThreadPool(2);
	private static final DateFormat ISO_8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

	public static void setup() {
		EndpointsV2.fileDownload("profile", "json", ProfileHandler::getJsonFileName, ProfileHandler::profileJson);
		EndpointsV2.fileDownload("profile", "zip", ProfileHandler::getZipFileName, ProfileHandler::profileZip);

		EndpointsV2.fileDownload("server", "json", ProfileHandler::getJsonFileName, ProfileHandler::serverJson);
	}

	private static String getJsonFileName(LoaderInfoV2 info) {
		return getJsonFileName(info, "json");
	}

	private static String getZipFileName(LoaderInfoV2 info) {
		return getJsonFileName(info, "zip");
	}

	private static String getJsonFileName(LoaderInfoV2 info, String ext) {
		return String.format("fabric-loader-%s-%s.%s", info.getLoader().getVersion(), info.getIntermediary().getVersion(), ext);
	}

	private static CompletableFuture<InputStream> profileJson(LoaderInfoV2 info) {
		return CompletableFuture.supplyAsync(() -> getProfileJsonStream(info, "client"), EXECUTOR);
	}

	private static CompletableFuture<InputStream> serverJson(LoaderInfoV2 info) {
		return CompletableFuture.supplyAsync(() -> getProfileJsonStream(info, "server"), EXECUTOR);
	}

	private static CompletableFuture<InputStream> profileZip(LoaderInfoV2 info) {
		return profileJson(info)
				.thenApply(inputStream -> packageZip(info, inputStream));
	}

	private static InputStream packageZip(LoaderInfoV2 info, InputStream profileJson)  {
		String profileName = String.format("fabric-loader-%s-%s", info.getLoader().getVersion(), info.getIntermediary().getVersion());

		try {
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

			try (ZipOutputStream zipStream = new ZipOutputStream(byteArrayOutputStream))  {
				//Write the profile json
				zipStream.putNextEntry(new ZipEntry(profileName + "/" + profileName + ".json"));
				IOUtils.copy(profileJson, zipStream);
				zipStream.closeEntry();

				//Write the dummy jar file
				zipStream.putNextEntry(new ZipEntry(profileName + "/" + profileName + ".jar"));
				zipStream.closeEntry();
			}

			//Is this really the best way??
			return new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
		} catch (IOException e) {
			throw new CompletionException(e);
		}
	}

	private static InputStream getProfileJsonStream(LoaderInfoV2 info, String side) {
		JsonObject jsonObject = buildProfileJson(info, side);
		return new ByteArrayInputStream(jsonObject.toString().getBytes());
	}

	private static final String MINECRAFT_MAVEN_URL = "https://libraries.minecraft.net/";

	//This is based of the installer code.
	private static JsonObject buildProfileJson(LoaderInfoV2 info, String side) {
		JsonObject launcherMeta = info.getLauncherMeta();

		String minecraftVersion = info.getIntermediary().getVersion();
		JsonObject versionMeta;

		try {
			versionMeta = FabricMeta.database.launcherMeta.get(minecraftVersion).getVersionMeta();
		} catch (IOException e) {
			throw new InternalServerErrorResponse("Failed to get minecraft version meta");
		}

		String profileName = String.format("fabric-loader-%s-%s", info.getLoader().getVersion(), minecraftVersion);

		JsonObject librariesObject = launcherMeta.get("libraries").getAsJsonObject();
		// Build the libraries array with the existing libs + loader and intermediary
		JsonArray libraries = (JsonArray) librariesObject.get("common");
		libraries.add(getLibrary(info.getIntermediary().getMaven(), LoaderMeta.MAVEN_URL));
		libraries.add(getLibrary(info.getLoader().getMaven(), LoaderMeta.MAVEN_URL));

		if (librariesObject.has(side)) {
			libraries.addAll(librariesObject.get(side).getAsJsonArray());
		}

		// Make sure old versions of asm are excluded
		JsonObject exclusionRule = new JsonObject();
		exclusionRule.addProperty("action", "disallow");
		libraries.add(getLibrary("org.ow2.asm:asm-all:*", "https://repo1.maven.org/maven2/", exclusionRule));

		// Cursed compatibility with cursed
		libraries.add(getLibrary("babric:log4j-config:1.0.0", LoaderMeta.MAVEN_URL));
		libraries.add(getLibrary("net.minecrell:terminalconsoleappender:1.2.0", "https://repo1.maven.org/maven2/"));
		libraries.add(getLibrary("org.slf4j:slf4j-api:1.8.0-beta4", MINECRAFT_MAVEN_URL));
		libraries.add(getLibrary("org.apache.logging.log4j:log4j-slf4j18-impl:2.16.0", MINECRAFT_MAVEN_URL));
		libraries.add(getLibrary("org.apache.logging.log4j:log4j-api:2.16.0", MINECRAFT_MAVEN_URL));
		libraries.add(getLibrary("org.apache.logging.log4j:log4j-core:2.16.0", MINECRAFT_MAVEN_URL));
		libraries.add(getLibrary("com.google.code.gson:gson:2.8.9", MINECRAFT_MAVEN_URL));
		libraries.add(getLibrary("com.google.guava:guava:31.0.1-jre", MINECRAFT_MAVEN_URL));
		libraries.add(getLibrary("org.apache.commons:commons-lang3:3.12.0", MINECRAFT_MAVEN_URL));
		libraries.add(getLibrary("commons-io:commons-io:2.11.0", MINECRAFT_MAVEN_URL));
		libraries.add(getLibrary("commons-codec:commons-codec:1.15", MINECRAFT_MAVEN_URL));

		// Use babric lwjgl
		if (side.equals("client")) {
			for (JsonElement library : versionMeta.getAsJsonArray("libraries")) {
				String name = library.getAsJsonObject().get("name").getAsString();
				if (name.contains("lwjgl") && name.contains("-babric.")) {
					libraries.add(library);
				}
			}
		}

		String currentTime = ISO_8601.format(new Date());

		JsonObject profile = new JsonObject();
		profile.addProperty("id", profileName);
		profile.addProperty("inheritsFrom", minecraftVersion);
		profile.addProperty("releaseTime", currentTime);
		profile.addProperty("time", currentTime);
		profile.addProperty("type", "release");

		JsonElement mainClassElement = launcherMeta.get("mainClass");
		String mainClass;

		if (mainClassElement.isJsonObject()) {
			mainClass = mainClassElement.getAsJsonObject().get(side).getAsString();
		} else {
			mainClass = mainClassElement.getAsString();
		}

		profile.addProperty("mainClass", mainClass);

		JsonObject arguments = new JsonObject();

		JsonArray gameArgs = new JsonArray();

		if (side.equals("client")) {
			JsonElement minecraftArguments = versionMeta.get("minecraftArguments");
			if (minecraftArguments != null) {
				for (String arg : minecraftArguments.getAsString().split(" ")) {
					gameArgs.add(arg);
				}
			}
		}

		arguments.add("game", gameArgs);

		if (side.equals("client")) {
			// add '-DFabricMcEmu= net.minecraft.client.main.Main ' to emulate vanilla MC presence for programs that check the process command line (discord, nvidia hybrid gpu, ..)
			JsonArray jvmArgs = new JsonArray();
			jvmArgs.add("-DFabricMcEmu= net.minecraft.client.main.Main ");

			jvmArgs.add("-cp");
			jvmArgs.add("${classpath}");
			jvmArgs.add("-Djava.library.path=${natives_directory}");

			arguments.add("jvm", jvmArgs);
		}

		profile.add("arguments", arguments);

		profile.add("libraries", libraries);

		return profile;
	}

	private static JsonObject getLibrary(String mavenPath, String url) {
		JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("name", mavenPath);
		jsonObject.addProperty("url", url);
		return jsonObject;
	}

	private static JsonObject getLibrary(String mavenPath, String url, JsonObject... rules) {
		JsonObject jsonObject = getLibrary(mavenPath, url);

		JsonArray jsonArray  = new JsonArray();
		for (JsonObject rule : rules) {
			jsonArray.add(rule);
		}

		jsonObject.add("rules", jsonArray);
		return jsonObject;
	}
}
