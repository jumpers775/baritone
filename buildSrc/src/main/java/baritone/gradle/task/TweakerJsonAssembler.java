/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.gradle.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kotlin.Pair;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import xyz.wagyourtail.unimined.api.Constants;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class TweakerJsonAssembler extends BaritoneGradleTask {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @OutputFile
    private File outFile;

    public void setOutFile(File outFile) {
        this.outFile = outFile;
    }

    public File getOutFile() {
        return outFile;
    }

    public Set<Configuration> getInternalConfigsForProject(Project project) {
        Set<Configuration> configs = new HashSet<>();
        configs.add(project.getConfigurations().getByName(Constants.MINECRAFT_COMBINED_PROVIDER));
        configs.add(project.getConfigurations().getByName(Constants.MINECRAFT_CLIENT_PROVIDER));
        configs.add(project.getConfigurations().getByName(Constants.MINECRAFT_SERVER_PROVIDER));
        configs.add(project.getConfigurations().getByName(Constants.MINECRAFT_LIBRARIES_PROVIDER));
        return configs;
    }

    public URL getURLOfArtifact(Dependency dep) {
        for (ArtifactRepository repo : getProject().getRepositories()) {
            try {
                if (repo instanceof MavenArtifactRepository) {
                    MavenArtifactRepository mavenRepo = (MavenArtifactRepository) repo;
                    URL url = mavenRepo.getUrl().toURL();
                    String jarURL = String.format(
                        "%s%s/%s/%s/%s-%s.jar",
                        url,
                        dep.getGroup().replace('.', '/'),
                        dep.getName(),
                        dep.getVersion(),
                        dep.getName(),
                        dep.getVersion()
                    );
                    HttpURLConnection connection = (HttpURLConnection) new URL(jarURL).openConnection();
                    connection.setRequestMethod("HEAD");
                    if (connection.getResponseCode() == 200) {
                        connection.disconnect();
                        return new URL(jarURL);
                    }
                    connection.disconnect();
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date());
    }

    @TaskAction
    public void exec() {
        getProject().getLogger().lifecycle("Assembling tweaker json");
        Set<Pair<Configuration, Dependency>> dependencies = new HashSet<>();
        for (Project project : new Project[] {getProject().getRootProject(), getProject()}) {
            Set<Configuration> internalConfigs = getInternalConfigsForProject(project);
            for (Configuration configuration : project.getConfigurations()) {
                if (internalConfigs.contains(configuration) || !configuration.isCanBeResolved()) {
                    continue;
                }
                configuration.resolve();
                dependencies.addAll(configuration.getAllDependencies().stream().map(dep -> new Pair<>(configuration, dep)).collect(Collectors.toList()));
            }
        }

        getProject().getLogger().info("Found {} dependencies", dependencies.size());

        SourceSetContainer sourceSets = getProject().getExtensions().getByType(SourceSetContainer.class);
        Set<Dependency> runtimeArtifacts = new HashSet<>();

        sourceSets.getByName("main").getRuntimeClasspath().forEach(file -> {
            for (Pair<Configuration, Dependency> dependency : dependencies) {
                if (dependency.getFirst().files(dependency.getSecond()).stream().anyMatch(file::equals)) {
                    runtimeArtifacts.add(dependency.getSecond());
                    getProject().getLogger().info("Found runtime artifact {}", dependency.getSecond());
                    return;
                }
            }
            getProject().getLogger().info("Found runtime artifact {} but it's not in the dependency list", file);
        });
        JsonObject json = new JsonObject();
        String mcVersion = (String) getProject().property("minecraft_version");
        json.addProperty("id", mcVersion);
        json.addProperty("type", "release");
        json.addProperty("inheritsFrom", mcVersion);
        json.addProperty("jar", mcVersion);
        String time = getCurrentTimestamp();
        json.addProperty("time", time);
        json.addProperty("releaseTime", time);
        json.add("downloads", new JsonObject());
        json.addProperty("minimumLauncherVersion", 0);
        json.addProperty("mainClass", "net.minecraft.launchwrapper.Launch");
        JsonObject args = new JsonObject();
        JsonArray game = new JsonArray();
        game.add("--tweakClass");
        game.add("baritone.launch.BaritoneTweaker");
        args.add("game", game);
        json.add("arguments", args);
        JsonArray libraries = new JsonArray();
        for (Dependency dep : runtimeArtifacts.stream().sorted(Comparator.comparing(Dependency::getName)).collect(Collectors.toList())) {
            if (dep.getGroup() == null) {
                getProject().getLogger().info("Group is null for " + dep.getName());
                continue;
            }
            JsonObject library = new JsonObject();
            library.addProperty("name", dep.getGroup() + ":" + dep.getName() + ":" + dep.getVersion());
            URL url = getURLOfArtifact(dep);
            if (url != null) {
                library.addProperty("url", url.toString());
            }
            libraries.add(library);
        }
        json.add("libraries", libraries);
        outFile.getParentFile().mkdirs();
        try (OutputStreamWriter os = new OutputStreamWriter(Files.newOutputStream(outFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            gson.toJson(json, os);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
