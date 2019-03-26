package org.codelibs.elasticsearch.velocity;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.codelibs.elasticsearch.velocity.script.VelocityScriptEngine;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;

public class VelocityPlugin extends Plugin implements ScriptPlugin {

    private Path configPath;

    public VelocityPlugin(final Settings settings, Path configPath) {
        this.configPath = configPath;
    }

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        return new VelocityScriptEngine(settings, configPath);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(VelocityScriptEngine.SETTING_SCRIPT_VELOCITY_PROPS,
                VelocityScriptEngine.SETTING_SCRIPT_VELOCITY_CONTEXT_PROPS);
    }
}
