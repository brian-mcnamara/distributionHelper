package net.bmac.intellij;

import com.google.common.collect.Lists;
import com.intellij.ide.ApplicationLoadListener;
import com.intellij.ide.plugins.*;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.ApplicationImpl;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.DumbProgressIndicator;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.util.Restarter;
import net.bmac.intellij.settings.Plugins;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ApplicationStartupHook implements ApplicationLoadListener {
    private static final String INSTALLED_SETTING = "distributionPlugin_ran";
    private static final String PLUGIN_FILE_PROPERTY = "distributionHelper.pluginFile";
    @Override
    public void beforeComponentsCreated() {
        if (PropertiesComponent.getInstance().isValueSet(INSTALLED_SETTING)) {
            return;
        }
        File pluginFile = getPluginFile();
        if (pluginFile == null) {
            return;
        }
        Plugins plugins = Plugins.load(pluginFile);

        addAllRepos(plugins);
        List<PluginNode> pluginNodes = getPluginsToInstall(plugins);

        if (!pluginNodes.isEmpty()) {
            List<IdeaPluginDescriptor> pluginDescriptors = getPlugins();
            try {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    boolean installed = PluginInstaller.prepareToInstall(pluginNodes, pluginDescriptors, new PluginManagerMain.PluginEnabler.HEADLESS(), DumbProgressIndicator.INSTANCE);
                    if (installed) {
                        if (Restarter.isSupported()) {
                            ((ApplicationImpl) ApplicationManager.getApplication()).exit(true, true, true);
                        }
                    }
                }).get();
            } catch (InterruptedException | ExecutionException e) {
                //TODO fixme;
                throw new RuntimeException(e);
            }
        }
        PropertiesComponent.getInstance().setValue(INSTALLED_SETTING, true);
    }

    private void addAllRepos(Plugins plugins) {
        List<String> repos = UpdateSettings.getInstance().getStoredPluginHosts();
        for (String repo : plugins.getPluginRepos()) {
            if (!repos.contains(repo)) repos.add(repo);
        }
    }

    private List<PluginNode> getPluginsToInstall(Plugins plugins) {
        List<String> pluginIds = plugins.getPluginIds();

        if (pluginIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<PluginId> installedIds = Stream.of(PluginManagerCore.getPlugins()).map(PluginDescriptor::getPluginId).collect(Collectors.toList());
        List<PluginNode> pluginNodes = Lists.newArrayListWithCapacity(pluginIds.size());
        for (String pluginId : pluginIds) {
            PluginId id = PluginId.getId(pluginId);
            if (installedIds.contains(id)) {
                continue;
            }
            PluginNode node = new PluginNode(id);
            node.setName(pluginId);
            pluginNodes.add(node);
        }
        return pluginNodes;
    }

    private List<IdeaPluginDescriptor> getPlugins() {
        try {
            return RepositoryHelper.loadCachedPlugins();
        } catch (IOException e) {

        }
        return Collections.emptyList();
    }

    private static File getPluginFile() {
        String filePath = System.getProperty(PLUGIN_FILE_PROPERTY);
        if (filePath != null) {
            File file = new File(filePath);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }
}
