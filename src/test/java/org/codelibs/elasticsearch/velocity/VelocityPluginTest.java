package org.codelibs.elasticsearch.velocity;

import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.newConfigs;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.codelibs.curl.CurlResponse;
import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.codelibs.elasticsearch.runner.net.EcrCurl;
import org.elasticsearch.action.DocWriteResponse.Result;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.node.Node;
import org.junit.After;
import org.junit.Test;

public class VelocityPluginTest {
    ElasticsearchClusterRunner runner;

    private File esHomeDir;

    private String clusterName;

    @After
    public void tearDown() throws Exception {
        runner.close();
        esHomeDir.delete();
    }

    protected void setupEs() throws Exception {
        clusterName = "es-velocity-" + System.currentTimeMillis();
        esHomeDir = File.createTempFile("eshome", "");
        esHomeDir.delete();

        final File scriptDir = new File(esHomeDir, "node_1/config/scripts");
        scriptDir.mkdirs();
        Files.write(new File(scriptDir, "lib_search_query_1.vm").toPath(),
                "{\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${my_size}\"}".getBytes());
        Files.write(new File(scriptDir, "VM_global_library.vm").toPath(),
                "#macro(macroSearchQuery){\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${my_size}\"}#end".getBytes());

        runner = new ElasticsearchClusterRunner();
        runner.onBuild(new ElasticsearchClusterRunner.Builder() {
            @Override
            public void build(final int number, final Builder settingsBuilder) {
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("http.cors.allow-origin", "*");
                settingsBuilder.put("discovery.type", "single-node");
                // settingsBuilder.putList("discovery.seed_hosts", "127.0.0.1:9301");
                // settingsBuilder.putList("cluster.initial_master_nodes", "127.0.0.1:9301");
            }
        }).build(newConfigs().clusterName(clusterName).numOfNode(1)
                .pluginTypes("org.codelibs.elasticsearch.velocity.VelocityPlugin,org.codelibs.elasticsearch.sstmpl.ScriptTemplatePlugin")
                .basePath(esHomeDir.getAbsolutePath()));
        runner.ensureGreen();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_search() throws Exception {
        setupEs();

        assertThat(1, is(runner.getNodeSize()));

        final Node node = runner.node();

        final String index = "sample";
        final String type = "data";
        runner.createIndex(index, Settings.builder().build());

        String query;

        query = "{\"script\":{\"lang\":\"velocity\",\"source\":"//
                + "\"{\\\"query\\\":{\\\"match\\\":{\\\"${my_field}\\\":\\\"${my_value}\\\"}},\\\"size\\\":\\\"${my_size}\\\"}\""//
                + "}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_scripts/index_search_query_1").header("Content-Type", "application/json").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
            assertThat(true, is(contentMap.get("acknowledged")));
        }

        for (int i = 1; i <= 1000; i++) {
            final IndexResponse indexResponse = runner.insert(index, type, String.valueOf(i),
                    "{\"id\":\"" + i + "\",\"msg\":\"test " + i + "\",\"counter\":" + i + ",\"category\":" + i % 10 + "}");
            assertEquals(Result.CREATED, indexResponse.getResult());
        }

        for (int loop = 0; loop < 100; loop++) {

            query = "{\"query\":{\"match_all\":{}}}";
            try (CurlResponse curlResponse = EcrCurl.post(node, "/" + index + "/" + type + "/_search")
                    .header("Content-Type", "application/json").body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
                assertThat(1000, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
                assertThat(10, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
            }

            query = "{\"template\":{\"query\":{\"match\":{\"{{my_field}}\":\"{{my_value}}\"}},\"size\":\"{{my_size}}\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = EcrCurl.post(node, "/" + index + "/" + type + "/_search/script_template")
                    .header("Content-Type", "application/json").body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
                assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
                assertThat(50, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
            }

            query = "{\"lang\":\"mustache\",\"template\":{\"query\":{\"match\":{\"{{my_field}}\":\"{{my_value}}\"}},\"size\":\"{{my_size}}\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = EcrCurl.post(node, "/" + index + "/" + type + "/_search/script_template")
                    .header("Content-Type", "application/json").body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
                assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
                assertThat(50, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":{\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${my_size}\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = EcrCurl.post(node, "/" + index + "/" + type + "/_search/script_template")
                    .header("Content-Type", "application/json").body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
                assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
                assertThat(50, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":\"#parse(\\\"lib_search_query_1.vm\\\")\","
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = EcrCurl.post(node, "/" + index + "/" + type + "/_search/script_template")
                    .header("Content-Type", "application/json").body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
                assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
                assertThat(50, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
            }

            query = "{\"lang\":\"velocity\",\"id\":\"index_search_query_1\","
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = EcrCurl.post(node, "/" + index + "/" + type + "/_search/script_template")
                    .header("Content-Type", "application/json").body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
                assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
                assertThat(50, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":\"#macroSearchQuery\","
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = EcrCurl.post(node, "/" + index + "/" + type + "/_search/script_template")
                    .header("Content-Type", "application/json").body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
                assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
                assertThat(50, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_search_withProp() throws Exception {
        clusterName = "es-velocity-" + System.currentTimeMillis();
        esHomeDir = File.createTempFile("eshome", "");
        esHomeDir.delete();

        final File confDir = new File(esHomeDir, "node_1/config");
        confDir.mkdirs();
        new File(confDir, "props").mkdirs();
        Files.write(new File(confDir, "file1.properties").toPath(), "my_size=5".getBytes());
        Files.write(new File(confDir, "props/file2.properties").toPath(),
                "my_size.0=1\nmy_size.1=2\nmy_size.2=3\nmy_size.3=4\nmy_size.4=5".getBytes());

        runner = new ElasticsearchClusterRunner();
        runner.onBuild(new ElasticsearchClusterRunner.Builder() {
            @Override
            public void build(final int number, final Builder settingsBuilder) {
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("http.cors.allow-origin", "*");
                settingsBuilder.put("script.velocity.props.ES_TMPL.resource.loader.modificationCheckInterval", "0");
                settingsBuilder.put("script.velocity.context.props.file1.interval", "0");
                settingsBuilder.put("script.velocity.context.props.file1", "file1.properties");
                settingsBuilder.put("script.velocity.context.props.file2", "props/file2.properties");
                settingsBuilder.put("script.velocity.context.props.file3", "file3.properties");
                settingsBuilder.put("discovery.type", "single-node");
                // settingsBuilder.putList("discovery.seed_hosts", "127.0.0.1:9301");
                // settingsBuilder.putList("cluster.initial_master_nodes", "127.0.0.1:9301");
            }
        }).build(newConfigs().clusterName(clusterName).numOfNode(1)
                .pluginTypes("org.codelibs.elasticsearch.velocity.VelocityPlugin,org.codelibs.elasticsearch.sstmpl.ScriptTemplatePlugin")
                .basePath(esHomeDir.getAbsolutePath()));
        runner.ensureGreen();

        assertThat(1, is(runner.getNodeSize()));

        final Node node = runner.node();

        final String index = "sample";
        final String type = "data";
        runner.createIndex(index, Settings.builder().build());

        for (int i = 1; i <= 1000; i++) {
            final IndexResponse indexResponse = runner.insert(index, type, String.valueOf(i),
                    "{\"id\":\"" + i + "\",\"msg\":\"test " + i + "\",\"counter\":" + i + ",\"category\":" + i % 10 + "}");
            assertEquals(Result.CREATED, indexResponse.getResult());
        }

        String query;

        query = "{\"script\":{\"lang\":\"velocity\",\"source\":"//
                + "\"{\\\"query\\\":{\\\"match\\\":{\\\"${my_field}\\\":\\\"${my_value}\\\"}},\\\"size\\\":\\\"${file1.my_size}\\\"}\""//
                + "}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_scripts/search_1").header("Content-Type", "application/json").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
            assertThat(true, is(contentMap.get("acknowledged")));
        }

        query = "{\"script\":{\"lang\":\"velocity\",\"source\":"//
                + "\"{\\\"query\\\":{\\\"match\\\":{\\\"${my_field}\\\":\\\"${my_value}\\\"}},\\\"size\\\":\\\"${file2.getAsList(\\\"my_size\\\")[2]}\\\"}\""//
                + "}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_scripts/search_2").header("Content-Type", "application/json").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
            assertThat(true, is(contentMap.get("acknowledged")));
        }

        query = "{\"id\":\"search_1\","
                + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_search/script_template").header("Content-Type", "application/json").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
            final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
            assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
            assertThat(5, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
        }

        query = "{\"id\":\"search_2\","
                + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_search/script_template").header("Content-Type", "application/json").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
            final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
            assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
            assertThat(3, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
        }

        Files.write(new File(confDir, "props/file2.properties").toPath(),
                "my_size.0=6\nmy_size.1=7\nmy_size.2=8\nmy_size.3=9\nmy_size.4=10".getBytes());

        Thread.sleep(5000L);

        query = "{\"id\":\"search_2\","
                + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_search/script_template").header("Content-Type", "application/json").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
            final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
            assertThat(100, is(((Map<String, Object>)hitsMap.get("total")).get("value")));
            assertThat(8, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
        }

    }

    @Test
    public void test_render() throws Exception {
        setupEs();

        assertThat(1, is(runner.getNodeSize()));

        final Node node = runner.node();

        String query;

        query = "{\"script\":{\"lang\":\"velocity\",\"source\":"//
                + "\"{\\\"query\\\":{\\\"match\\\":{\\\"${my_field}\\\":\\\"${my_value}\\\"}},\\\"size\\\":\\\"${my_size}\\\"}\""//
                + "}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_scripts/search_1").header("Content-Type", "application/json").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContent(EcrCurl.jsonParser());
            assertThat(true, is(contentMap.get("acknowledged")));
        }

        query = "{\"lang\":\"velocity\",\"inline\":\"{\\\"query\\\":{\\\"match\\\":{\\\"$my_field\\\":\\\"$my_value\\\"}},\\\"size\\\":\\\"$my_size\\\"}\","
                + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_render/script_template").header("Content-Type", "application/json").body(query).execute()) {
            final String content = curlResponse.getContentAsString();
            assertEquals("{\"template_output\":{\"query\":{\"match\":{\"category\":\"1\"}},\"size\":\"50\"}}", content);
        }

        query = "{\"id\":\"search_1\"," + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
        try (CurlResponse curlResponse =
                EcrCurl.post(node, "/_render/script_template").header("Content-Type", "application/json").body(query).execute()) {
            final String content = curlResponse.getContentAsString();
            assertEquals("{\"template_output\":{\"query\":{\"match\":{\"category\":\"1\"}},\"size\":\"50\"}}", content);
        }
    }
}
