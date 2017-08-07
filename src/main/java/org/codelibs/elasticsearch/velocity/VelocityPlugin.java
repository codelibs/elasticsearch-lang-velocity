package org.codelibs.elasticsearch.velocity;

import java.util.Arrays;
import java.util.List;

import org.codelibs.elasticsearch.velocity.service.VelocityScriptEngineService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScriptEngineService;

public class VelocityPlugin extends Plugin implements ScriptPlugin {

    @Override
    public ScriptEngineService getScriptEngineService(final Settings settings) {
        return new VelocityScriptEngineService(settings);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(VelocityScriptEngineService.SETTING_SCRIPT_VELOCITY_PROPS,
                VelocityScriptEngineService.SETTING_SCRIPT_VELOCITY_CONTEXT_PROP_FILE,
                VelocityScriptEngineService.SETTING_SCRIPT_VELOCITY_CONTEXT_PROP_INTERVAL);
    }
}
