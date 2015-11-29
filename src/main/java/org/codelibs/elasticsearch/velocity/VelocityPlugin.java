package org.codelibs.elasticsearch.velocity;

import org.codelibs.elasticsearch.velocity.service.VelocityScriptEngineService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptModule;

public class VelocityPlugin extends Plugin {
    @Override
    public String name() {
        return "lang-velocity";
    }

    @Override
    public String description() {
        return "This plugin provides Velocity language as a script.";
    }

    public void onModule(final ScriptModule module) {
        module.addScriptEngine(VelocityScriptEngineService.class);
    }

}
