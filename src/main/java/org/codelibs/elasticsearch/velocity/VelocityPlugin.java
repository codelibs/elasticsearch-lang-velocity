package org.codelibs.elasticsearch.velocity;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.codelibs.elasticsearch.velocity.service.VelocityScriptEngineService;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;

public class VelocityPlugin extends Plugin implements ScriptPlugin {

    private VelocityScriptEngineService scriptEngineService;

    @Override
    public ScriptEngineService getScriptEngineService(final Settings settings) {
        scriptEngineService = new VelocityScriptEngineService(settings);
        return scriptEngineService;
    }

    public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService, ScriptService scriptService, NamedXContentRegistry xContentRegistry) {
        scriptEngineService.setThreadContext(threadPool.getThreadContext());
        return Collections.emptyList();
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(VelocityScriptEngineService.SETTING_SCRIPT_VELOCITY_PROPS,
                VelocityScriptEngineService.SETTING_SCRIPT_VELOCITY_CONTEXT_PROP_FILE,
                VelocityScriptEngineService.SETTING_SCRIPT_VELOCITY_CONTEXT_PROP_INTERVAL);
    }
}
