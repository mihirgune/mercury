/*

    Copyright 2018-2023 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.core;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.platformlambda.core.util.AppConfigReader;
import org.platformlambda.core.util.ConfigReader;
import org.platformlambda.core.util.MultiLevelMap;
import org.platformlambda.core.util.Utility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ConfigReaderTest {

    private static final String CONFIG_LOOP = "* config loop *";

    @BeforeClass
    public static void setup() {
        ConfigReader.setBaseConfig(AppConfigReader.getInstance());
    }

    @Test
    public void environmentVarSubstitution() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.properties");
        String path = System.getenv("PATH");
        Assert.assertEquals(path, reader.getProperty("hello.world"));
    }

    @Test
    public void systemPropertySubstitution() throws IOException {
        final String HELLO = "HELLO";
        System.setProperty("sample.system.property", HELLO);
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.properties");
        Assert.assertEquals(HELLO, reader.getProperty("my.system.property"));
    }

    @Test
    public void systemPropertyIsAlwaysAvailable() {
        final String NON_EXIST_PROPERTY = "parameter.not.found.in.application.properties";
        final String HELLO = "HELLO";
        System.setProperty(NON_EXIST_PROPERTY, HELLO);
        AppConfigReader config = AppConfigReader.getInstance();
        Assert.assertEquals(HELLO, config.getProperty(NON_EXIST_PROPERTY));
    }

    @Test
    public void getValueFromParent() throws IOException {
        AppConfigReader parent = AppConfigReader.getInstance();
        String parentValue = parent.getProperty("cloud.connector");
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.properties");
        String subordinateValue = reader.getProperty("my.cloud.connector");
        Assert.assertEquals(parentValue, subordinateValue);
    }

    @Test
    public void getDefaultValue() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.properties");
        String value = reader.getProperty("another.key");
        Assert.assertEquals("12345", value);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void dotFormatterTest() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.yaml");
        Object o = reader.get("hello.world");
        Assert.assertEquals("some value", o);
        o = reader.get("hello.multiline");
        Assert.assertTrue(o instanceof String);
        Assert.assertTrue(o.toString().contains("\n"));
        List<String> lines = Utility.getInstance().split(o.toString(), "\n");
        Assert.assertEquals(2, lines.size());
        Assert.assertEquals("line one", lines.get(0));
        Assert.assertEquals("line two", lines.get(1));
        o = reader.get("hello.array");
        Assert.assertTrue(o instanceof ArrayList);
        List<String> elements = (List<String>) o;
        Assert.assertEquals(2, elements.size());
        Assert.assertEquals("hi", elements.get(0));
        Assert.assertEquals("this is great", elements.get(1));
        o = reader.get("hello.array[0]");
        Assert.assertEquals("hi", o);
        o = reader.get("hello.array[1]");
        Assert.assertEquals("this is great", o);
    }

    @Test
    public void flattenMapTest() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.yml");
        Map<String, Object> map = Utility.getInstance().getFlatMap(reader.getMap());
        Assert.assertEquals("some value", map.get("hello.world"));
        Assert.assertEquals("hi", map.get("hello.array[0]"));
        Assert.assertEquals("this is great", map.get("hello.array[1]"));
        /*
         * Unlike properties file that converts values into strings,
         * YAML and JSON preserve original objects.
         */
        Object o = map.get("hello.number");
        Assert.assertTrue(o instanceof Integer);
        Assert.assertEquals(12345, o);
    }

    @Test
    public void appConfigTest() {
        // AppConfigReader will combine both application.properties and application.yml
        AppConfigReader reader = AppConfigReader.getInstance();
        // application.name is stored in "application.properties"
        Assert.assertEquals("platform-core", reader.getProperty("application.name"));
        // hello.world is stored in "application.yml"
        Assert.assertEquals("great", reader.get("hello.world"));
    }

    @Test
    public void parameterSubstitutionTest() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.yaml");
        Assert.assertEquals("platform-core", reader.getProperty("hello.name"));
        AppConfigReader config = AppConfigReader.getInstance();
        Assert.assertEquals("8100", reader.getProperty("hello.location[0]"));
        Assert.assertEquals("http://127.0.0.1:"+config.getProperty("server.port")+"/info",
                                reader.getProperty("hello.location[1]"));
        Assert.assertEquals(100, reader.get("hello.location[2].world.blvd"));
        Assert.assertEquals(config.getProperty("server.port")+" is server port",
                                reader.getProperty("hello.location[3]"));
        Assert.assertEquals("Server port is "+config.getProperty("server.port"),
                                reader.getProperty("hello.location[4]"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void dotFormatterSetTest() throws IOException {
        // generate random top level key
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String goodDay = uuid+".great.day";
        String goodArray = uuid+".array";
        String message = "test message";
        Integer input = 123456789;
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.yaml");
        int size = reader.getMap().size();
        MultiLevelMap formatter = new MultiLevelMap(reader.getMap());
        formatter.setElement(goodDay, input).setElement(goodArray+"[1]", message);
        Object o = formatter.getElement(goodDay);
        Assert.assertEquals(input, o);
        // confirm added only one key at the top level
        Assert.assertEquals(size+1, formatter.getMap().size());
        Assert.assertNull(formatter.getElement(goodArray+"[0]"));
        Assert.assertEquals(message, formatter.getElement(goodArray+"[1]"));
        o = formatter.getElement(uuid);
        Assert.assertTrue(o instanceof Map);
        Map<String, Object> submap = (Map<String, Object>) o;
        Assert.assertEquals(2, submap.size());
        Assert.assertTrue(submap.containsKey("great"));
        Assert.assertTrue(submap.containsKey("array"));
    }

    @Test
    public void resourceNotFound() {
        IOException ex = Assert.assertThrows(IOException.class, () -> {
            ConfigReader reader = new ConfigReader();
            reader.load("classpath:/notfound.yaml");
        });
        Assert.assertEquals("classpath:/notfound.yaml not found", ex.getMessage());
    }

    @Test
    public void fileNotFound() {
        IOException ex = Assert.assertThrows(IOException.class, () -> {
            ConfigReader reader = new ConfigReader();
            reader.load("file:/notfound.yaml");
        });
        Assert.assertEquals("file:/notfound.yaml not found", ex.getMessage());
    }

    @Test
    public void jsonReadTest() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.json");
        Assert.assertEquals(2, reader.getMap().size());
        Assert.assertEquals("world", reader.get("hello"));
        Assert.assertEquals("message", reader.get("test"));
    }

    @Test
    public void getPropertyFromEnv() {
        AppConfigReader config = AppConfigReader.getInstance();
        String value = config.getProperty("system.path");
        String path = System.getenv("PATH");
        Assert.assertEquals(path, value);
    }

    @Test
    public void getPropertyFromEnvFromAnotherConfig() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.yaml");
        Object value = reader.get("hello.path");
        String path = System.getenv("PATH");
        Assert.assertEquals(path, value);
    }

    @Test
    public void singleLevelLoopErrorTest() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.properties");
        String value = reader.getProperty("recursive.key");
        // In case of config loop, the system will not resolve a parameter value.
        Assert.assertEquals(CONFIG_LOOP, value);
    }

    @Test
    public void multiLevelLoopErrorTest() {
        AppConfigReader config = AppConfigReader.getInstance();
        Object value = config.get("looping.test");
        Assert.assertEquals(CONFIG_LOOP, value);
    }

    @Test
    public void defaultValueTest() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.yaml");
        String value = reader.getProperty("test.no.value", "hello world");
        Assert.assertEquals("hello world", value);
    }
    @Test
    public void defaultValueInRefTest() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.yaml");
        String value = reader.getProperty("test.default");
        Assert.assertEquals("hello 1000", value);
    }

    @Test
    public void noDefaultValueInRefTest() throws IOException {
        ConfigReader reader = new ConfigReader();
        reader.load("classpath:/test.yaml");
        String value = reader.getProperty("test.no_default");
        // when there is no default value in the reference, it will return empty string as the default value.
        Assert.assertEquals("hello world", value);
    }

}
