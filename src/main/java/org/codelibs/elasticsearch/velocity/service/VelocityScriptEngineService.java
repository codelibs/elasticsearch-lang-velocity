package org.codelibs.elasticsearch.velocity.service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.VelocityException;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.UTF8StreamWriter;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.lookup.SearchLookup;

public class VelocityScriptEngineService extends AbstractComponent implements
        ScriptEngineService {

    public static String VELOCITY_SCRIPT_PROPS = "script.velocity.props.";

    /**
     * Thread local UTF8StreamWriter to store template execution results in,
     * thread local to save object creation.
     */
    private static ThreadLocal<SoftReference<UTF8StreamWriter>> utf8StreamWriter = new ThreadLocal<>();

    private VelocityEngine velocity;

    private File workDir;

    /**
     * If exists, reset and return, otherwise create, reset and return a writer.
     */
    private static UTF8StreamWriter utf8StreamWriter() {
        final SoftReference<UTF8StreamWriter> ref = utf8StreamWriter.get();
        UTF8StreamWriter writer = ref == null ? null : ref.get();
        if (writer == null) {
            writer = new UTF8StreamWriter(1024 * 4);
            utf8StreamWriter.set(new SoftReference<>(writer));
        }
        writer.reset();
        return writer;
    }

    @Inject
    public VelocityScriptEngineService(final Settings settings,
            final Environment env) {
        super(settings);

        workDir = new File(env.workFile(), "velocity");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new VelocityException(
                    "Could not create a working directory: "
                            + workDir.getAbsolutePath());
        }

        final Properties props = new Properties();
        for (final Map.Entry<String, String> entry : settings
                .getByPrefix(VELOCITY_SCRIPT_PROPS).getAsMap().entrySet()) {
            props.put(entry.getKey(), entry.getValue());
        }
        if (initPropertyValue(props, "resource.loader", "FILE")) {
            initPropertyValue(props, "FILE.resource.loader.class",
                    "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
            initPropertyValue(props, "FILE.resource.loader.path",
                    workDir.getAbsolutePath());
            initPropertyValue(props, "FILE.resource.loader.cache", "true");
            initPropertyValue(props,
                    "FILE.resource.loader.modificationCheckInterval", "60");
        }
        initPropertyValue(props, "input.encoding", "UTF-8");
        initPropertyValue(props, "output.encoding", "UTF-8");
        initPropertyValue(props, "runtime.log", new File(env.logsFile(),
                "velocity.log").getAbsolutePath());

        velocity = new VelocityEngine(props);

        velocity.init();
    }

    private boolean initPropertyValue(final Properties props, final String key,
            final String value) {
        if (!props.containsKey(key)) {
            props.put(key, value);
            return true;
        }
        return false;
    }

    @Override
    public String[] types() {
        return new String[] { "velocity" };
    }

    @Override
    public String[] extensions() {
        return new String[] { "vm", "velocity" };
    }

    @Override
    public boolean sandboxed() {
        return false;
    }

    @Override
    public Object compile(final String script) {
        String encoding = (String) velocity.getProperty("input.encoding");
        if (encoding == null) {
            encoding = "UTF-8";
        }

        File templateFile = null;
        BufferedWriter bw = null;
        try {
            templateFile = File.createTempFile("templ", ".vm", workDir);
            bw = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(templateFile), encoding));
            bw.write(script);
            bw.flush();
        } catch (final IOException e) {
            throw new VelocityException("Failed to create a template file.", e);
        } finally {
            if (bw != null) {
                try {
                    bw.close();
                } catch (final IOException e) {
                    // ignore
                }
            }
        }

        return new VelocityScriptContext(templateFile,
                velocity.getTemplate(script));
    }

    @Override
    public ExecutableScript executable(final Object template,
            final Map<String, Object> vars) {
        return new VelocityExecutableScript((VelocityScriptContext) template,
                vars);
    }

    @Override
    public SearchScript search(final Object template,
            final SearchLookup lookup, final Map<String, Object> vars) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object execute(final Object template, final Map<String, Object> vars) {
        final BytesStreamOutput result = new BytesStreamOutput();
        final UTF8StreamWriter writer = utf8StreamWriter().setOutput(result);

        try {
            ((VelocityScriptContext) template).getTemplate().merge(
                    new VelocityContext(vars), writer);
            writer.flush();
        } catch (final IOException e) {
            logger.error(
                    "Could not execute query template (failed to flush writer): ",
                    e);
        } finally {
            try {
                writer.close();
            } catch (final IOException e) {
                logger.error(
                        "Could not execute query template (failed to close writer): ",
                        e);
            }
        }
        return result.bytes();
    }

    @Override
    public Object unwrap(final Object value) {
        return value;
    }

    @Override
    public void scriptRemoved(final CompiledScript script) {
        final File templateFile = ((VelocityScriptContext) script.compiled())
                .getTemplateFile();
        if (!templateFile.delete()) {
            logger.warn("Failed to delete {}.", templateFile.getAbsolutePath());
        }
    }

    @Override
    public void close() {
        // Nothing to do here
    }

    private static class VelocityScriptContext {
        private Template template;

        private File templateFile;

        public VelocityScriptContext(final File templateFile,
                final Template template) {
            this.templateFile = templateFile;
            this.template = template;
        }

        public Template getTemplate() {
            return template;
        }

        public File getTemplateFile() {
            return templateFile;
        }
    }

    private class VelocityExecutableScript implements ExecutableScript {
        /** Compiled template object. */
        private VelocityScriptContext context;

        /** Parameters to fill above object with. */
        private Map<String, Object> vars;

        /**
         * @param template
         *            the compiled template object
         * @param vars
         *            the parameters to fill above object with
         **/
        public VelocityExecutableScript(final VelocityScriptContext context,
                final Map<String, Object> vars) {
            this.context = context;
            this.vars = vars == null ? Collections.EMPTY_MAP : vars;
        }

        @Override
        public void setNextVar(final String name, final Object value) {
            vars.put(name, value);
        }

        @Override
        public Object run() {
            final BytesStreamOutput result = new BytesStreamOutput();
            final UTF8StreamWriter writer = utf8StreamWriter()
                    .setOutput(result);

            try {
                context.getTemplate().merge(new VelocityContext(vars), writer);
                writer.flush();
            } catch (final Exception e) {
                logger.error(
                        "Could not execute query template (failed to flush writer): ",
                        e);
            } finally {
                try {
                    writer.close();
                } catch (final IOException e) {
                    logger.error(
                            "Could not execute query template (failed to close writer): ",
                            e);
                }
            }
            return result.bytes();
        }

        @Override
        public Object unwrap(final Object value) {
            return value;
        }
    }

}
