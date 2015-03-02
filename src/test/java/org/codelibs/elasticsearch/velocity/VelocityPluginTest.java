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
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.ImmutableSettings.Builder;
import org.elasticsearch.node.Node;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.io.Files;

public class VelocityPluginTest {
    ElasticsearchClusterRunner runner;

    private File esHomeDir;

    @Before
    public void setUp() throws Exception {
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
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("script.disable_dynamic", false);
            }
        }).build(
                newConfigs().clusterName("es-lang-velocity").numOfNode(1)
                        .ramIndexStore().basePath(esHomeDir.getAbsolutePath()));
        runner.ensureGreen();
    }

    @After
    public void tearDown() throws Exception {
        runner.close();
        esHomeDir.delete();
    }

    @Test
    public void test_search() throws Exception {

        assertThat(1, is(runner.getNodeSize()));

        final Node node = runner.node();

        final String index = "sample";
        final String type = "data";
        runner.createIndex(index, ImmutableSettings.builder().build());

        for (int i = 1; i <= 1000; i++) {
            final IndexResponse indexResponse = runner.insert(index, type,
                    String.valueOf(i), "{\"id\":\"" + i + "\",\"msg\":\"test "
                            + i + "\",\"counter\":" + i + ",\"category\":" + i
                            % 10 + "}");
            assertTrue(indexResponse.isCreated());
        }

        try (CurlResponse curlResponse = Curl
                .post(node, "/_scripts/velocity/index_search_query_1")
                .body("{\"query\":{\"match\":{\"${my_field}\":\"${my_value}\"}},\"size\":\"${my_size}\"}")
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
}
