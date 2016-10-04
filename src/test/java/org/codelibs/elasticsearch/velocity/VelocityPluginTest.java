package org.codelibs.elasticsearch.velocity;

import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.newConfigs;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.codelibs.elasticsearch.runner.net.Curl;
import org.codelibs.elasticsearch.runner.net.CurlResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.node.Node;
import org.junit.After;
import org.junit.Test;

import com.google.common.io.Files;

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

        final File scriptDir = new File(esHomeDir, "config/node_1/scripts");
        scriptDir.mkdirs();
        Files.write(
                "{\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${my_size}\"}"
                        .getBytes(), new File(scriptDir,
                        "lib_search_query_1.vm"));
        Files.write(
                "{\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${my_size}\"}"
                        .getBytes(), new File(scriptDir, "search_query_1.vm"));
        Files.write(
                "##cache\n{\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${my_size}\"}"
                        .getBytes(), new File(scriptDir, "search_query_2.vm"));
        Files.write("#parse(\"lib_search_query_1.vm\")".getBytes(), new File(
                scriptDir, "search_query_3.vm"));
        Files.write(
                "#macro(macroSearchQuery){\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${my_size}\"}#end"
                        .getBytes(),
                new File(scriptDir, "VM_global_library.vm"));

        runner = new ElasticsearchClusterRunner();
        runner.onBuild(new ElasticsearchClusterRunner.Builder() {
            @Override
            public void build(final int number, final Builder settingsBuilder) {
                settingsBuilder.put("script.velocity.work_dir",
                        "/tmp/es-velocity");
                settingsBuilder.put("script.inline", "on");
                settingsBuilder.put("script.indexed", "on");
                settingsBuilder.put("script.file", "on");
                settingsBuilder.put("script.search", "on");
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("http.cors.allow-origin", "*");
                settingsBuilder.put("index.number_of_shards", 3);
                settingsBuilder.put("index.number_of_replicas", 0);
                settingsBuilder.putArray("discovery.zen.ping.unicast.hosts",
                        "localhost:9301-9310");
                settingsBuilder.put("plugin.types",
                        "org.codelibs.elasticsearch.velocity.VelocityPlugin,org.codelibs.elasticsearch.sstmpl.ScriptTemplatePlugin");
                settingsBuilder
                        .put("index.unassigned.node_left.delayed_timeout", "0");
            }
        }).build(newConfigs().clusterName(clusterName).numOfNode(1)
                .basePath(esHomeDir.getAbsolutePath()));
        runner.ensureGreen();
    }

    @Test
    public void test_search() throws Exception {
        setupEs();

        assertThat(1, is(runner.getNodeSize()));

        final Node node = runner.node();

        final String index = "sample";
        final String type = "data";
        runner.createIndex(index, Settings.builder().build());

        for (int i = 1; i <= 1000; i++) {
            final IndexResponse indexResponse = runner.insert(index, type,
                    String.valueOf(i), "{\"id\":\"" + i + "\",\"msg\":\"test "
                            + i + "\",\"counter\":" + i + ",\"category\":" + i
                            % 10 + "}");
            assertTrue(indexResponse.isCreated());
        }

        try (CurlResponse curlResponse = Curl
                .post(node, "/_scripts/velocity/index_search_query_1")
                .body("{\"script\":\"{\\\"query\\\":{\\\"match\\\":{\\\"${my_field}\\\":\\\"${my_value}\\\"}},\\\"size\\\":\\\"${my_size}\\\"}\"}")
                .execute()) {
            assertThat(201, is(curlResponse.getHttpStatusCode()));
        }

        for (int loop = 0; loop < 100; loop++) {

            String query;
            query = "{\"query\":{\"match_all\":{}}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(1000, is(hitsMap.get("total")));
                assertThat(10,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }

            query = "{\"template\":{\"query\":{\"match\":{\"{{my_field}}\":\"{{my_value}}\"}},\"size\":\"{{my_size}}\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search/template")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(100, is(hitsMap.get("total")));
                assertThat(50,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }

            query = "{\"lang\":\"mustache\",\"template\":{\"query\":{\"match\":{\"{{my_field}}\":\"{{my_value}}\"}},\"size\":\"{{my_size}}\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search/template")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(100, is(hitsMap.get("total")));
                assertThat(50,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":\"#parse(\\\"lib_search_query_1.vm\\\")\","
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search/template")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(100, is(hitsMap.get("total")));
                assertThat(50,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":{\"id\":\"index_search_query_1\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search/template")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(100, is(hitsMap.get("total")));
                assertThat(50,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":{\"file\":\"search_query_1\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search/template")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(100, is(hitsMap.get("total")));
                assertThat(50,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":{\"file\":\"search_query_2\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search/template")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(100, is(hitsMap.get("total")));
                assertThat(50,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":{\"file\":\"search_query_3\"},"
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search/template")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(100, is(hitsMap.get("total")));
                assertThat(50,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }

            query = "{\"lang\":\"velocity\",\"template\":\"#macroSearchQuery\","
                    + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
            try (CurlResponse curlResponse = Curl
                    .post(node, "/" + index + "/" + type + "/_search/template")
                    .body(query).execute()) {
                final Map<String, Object> contentMap = curlResponse
                        .getContentAsMap();
                final Map<String, Object> hitsMap = (Map<String, Object>) contentMap
                        .get("hits");
                assertThat(100, is(hitsMap.get("total")));
                assertThat(50,
                        is(((List<Map<String, Object>>) hitsMap.get("hits"))
                                .size()));
            }
        }
    }

    @Test
    public void test_search_withProp() throws Exception {
        clusterName = "es-velocity-" + System.currentTimeMillis();
        esHomeDir = File.createTempFile("eshome", "");
        esHomeDir.delete();

        final File scriptDir = new File(esHomeDir, "config/node_1/scripts");
        scriptDir.mkdirs();
        Files.write("{\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${file1.my_size}\"}".getBytes(),
                new File(scriptDir, "search_1.vm"));
        Files.write("{\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${file2.getAsList(\"my_size\")[2]}\"}".getBytes(),
                new File(scriptDir, "search_2.vm"));

        final File confDir = new File(esHomeDir, "config/node_1");
        confDir.mkdirs();
        new File(confDir, "props").mkdirs();
        Files.write("my_size=5".getBytes(), new File(confDir, "file1.properties"));
        Files.write("my_size.0=1\nmy_size.1=2\nmy_size.2=3\nmy_size.3=4\nmy_size.4=5".getBytes(),
                new File(confDir, "props/file2.properties"));

        runner = new ElasticsearchClusterRunner();
        runner.onBuild(new ElasticsearchClusterRunner.Builder() {
            @Override
            public void build(final int number, final Builder settingsBuilder) {
                settingsBuilder.put("script.inline", "on");
                settingsBuilder.put("script.indexed", "on");
                settingsBuilder.put("script.file", "on");
                settingsBuilder.put("script.search", "on");
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("http.cors.allow-origin", "*");
                settingsBuilder.put("index.number_of_shards", 3);
                settingsBuilder.put("index.number_of_replicas", 0);
                settingsBuilder.put("script.velocity.context.props.file1", "file1.properties");
                settingsBuilder.put("script.velocity.context.props.file1.interval", "0");
                settingsBuilder.put("script.velocity.context.props.file2", "props/file2.properties");
                settingsBuilder.put("script.velocity.context.props.file3", "file3.properties");
                settingsBuilder.put("script.velocity.props.ES_TMPL.resource.loader.modificationCheckInterval", "0");                
                settingsBuilder.putArray("discovery.zen.ping.unicast.hosts", "localhost:9301-9310");
                settingsBuilder.put("plugin.types",
                        "org.codelibs.elasticsearch.velocity.VelocityPlugin,org.codelibs.elasticsearch.sstmpl.ScriptTemplatePlugin");
                settingsBuilder.put("index.unassigned.node_left.delayed_timeout", "0");
            }
        }).build(newConfigs().clusterName(clusterName).numOfNode(1).basePath(esHomeDir.getAbsolutePath()));
        runner.ensureGreen();


        assertThat(1, is(runner.getNodeSize()));

        final Node node = runner.node();

        final String index = "sample";
        final String type = "data";
        runner.createIndex(index, Settings.builder().build());

        for (int i = 1; i <= 1000; i++) {
            final IndexResponse indexResponse = runner.insert(index, type, String.valueOf(i),
                    "{\"id\":\"" + i + "\",\"msg\":\"test " + i + "\",\"counter\":" + i + ",\"category\":" + i % 10 + "}");
            assertTrue(indexResponse.isCreated());
        }

        String query;

        query = "{\"lang\":\"velocity\",\"file\":\"search_1\","
                + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
        try (CurlResponse curlResponse = Curl.post(node, "/_search/template").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContentAsMap();
            final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
            assertThat(100, is(hitsMap.get("total")));
            assertThat(5, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
        }

        query = "{\"lang\":\"velocity\",\"file\":\"search_2\","
                + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
        try (CurlResponse curlResponse = Curl.post(node, "/_search/template").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContentAsMap();
            final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
            assertThat(100, is(hitsMap.get("total")));
            assertThat(3, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
        }

        Files.write("my_size.0=6\nmy_size.1=7\nmy_size.2=8\nmy_size.3=9\nmy_size.4=10".getBytes(),
                new File(confDir, "props/file2.properties"));

        Thread.sleep(5000L);
        
        query = "{\"lang\":\"velocity\",\"file\":\"search_2\","
                + "\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"50\"}}";
        try (CurlResponse curlResponse = Curl.post(node, "/_search/template").body(query).execute()) {
            final Map<String, Object> contentMap = curlResponse.getContentAsMap();
            final Map<String, Object> hitsMap = (Map<String, Object>) contentMap.get("hits");
            assertThat(100, is(hitsMap.get("total")));
            assertThat(8, is(((List<Map<String, Object>>) hitsMap.get("hits")).size()));
        }

    }
}
