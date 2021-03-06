package com.yoozoo.protoconf;

import com.coreos.jetcd.Client;
import com.coreos.jetcd.Txn;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.kv.GetResponse;
import com.coreos.jetcd.op.Op;
import com.coreos.jetcd.options.GetOption;
import com.coreos.jetcd.options.WatchOption;
import com.coreos.jetcd.watch.WatchResponse;
import com.coreos.jetcd.Watch.Watcher;
import com.coreos.jetcd.watch.WatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EtcdReader implements ConfigurationReader.KVReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(EtcdReader.class);
    private String envkey;
    Client client;

    public EtcdReader() {
        try {
//            getting etcd client username and password and endpoints from env var
            String userName = System.getenv("etcd_user").split(":")[0];
            String password = System.getenv("etcd_user").split(":")[1];
            String endpoints = System.getenv("etcd_endpoints");
            envkey = "/" + System.getenv("etcd_envkey");
            client = Client.builder().user(ByteSequence.fromString(userName)).password(ByteSequence.fromString(password)).endpoints(endpoints).build();

        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage());
        }

    }

    //  client auth is on
    public EtcdReader(String url, String userName, String password) {
        try {
            //      create and authenticate etcd client
            client = Client.builder().user(ByteSequence.fromString(userName)).password(ByteSequence.fromString(password)).endpoints(url).build();

        } catch (Exception e) {
//            failed to authenticate
            LOGGER.error(e.getLocalizedMessage());
        }

    }

    //    get all key-values pairs for an application
    public Map<String, String> getValues(String appName, String[] keys) {
        try {
            appName = "/" + appName;
//            initiate a transaction
            Txn txn = client.getKVClient().txn();
            for (String key : keys) {
//                for each keys retrieved from config class, get the value from etcd client
                txn = txn.Then(Op.get(ByteSequence.fromString( envkey  + appName + "/" + key), GetOption.DEFAULT));
            }
            List<GetResponse> responses = txn.commit().get().getGetResponses();

            HashMap<String, String> result = new HashMap<>();
            for (String key : keys) {
                result.put(key, null);
            }

            for (GetResponse response : responses) {
                if (response.getCount() == 1) {
//                    if the value can be found, put the value in the result map
                    KeyValue kv = response.getKvs().get(0);
                    result.replace(kv.getKey().toStringUtf8().substring((envkey + appName + "/").length()), kv.getValue().toStringUtf8());
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage());
        }

        return null;
    }

    //    put a value to a key (not used for fow)
    public void setValue(String key, String value) {
        try {
            client.getKVClient().put(
                    ByteSequence.fromString(key),
                    ByteSequence.fromString(value)
            ).get();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    //  get a value for a key
    public String getValue(String key) {
        try {
// get String value
            GetResponse getResponse = client.getKVClient().get(ByteSequence.fromString(key)).get();
            if (getResponse.getKvs().isEmpty()) {
                // key does not exist
                return null;
            }

            return getResponse.getKvs().get(0).getValue().toStringUtf8();
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage());
        }

        return null;
    }

    //    watch value updates and change accordingly
    public void watchKeys(ConfigurationInterface data) {
//        start a thread to watch key prefix with app name
        new Thread(() -> {
            String appName = "/" + data.applicationName();
            Watcher watcher = null;
            try {
                WatchOption watchOption = WatchOption.newBuilder().withPrefix(ByteSequence.fromString(envkey + appName + "/")).build();
                watcher = client.getWatchClient().watch(ByteSequence.fromString(""), watchOption);

                while (true) {
                    Map<String, String> changeMap = new HashMap<>();

                    WatchResponse response = watcher.listen();
                    for (WatchEvent event : response.getEvents()) {
                        if (event.getEventType().equals(WatchEvent.EventType.PUT)) {
                            changeMap.put(event.getKeyValue().getKey().toStringUtf8().substring((envkey + appName + "/").length()), event.getKeyValue().getValue().toStringUtf8());
                        }
                    }

                    for(Map.Entry<String, String> entry: changeMap.entrySet()) {
                        data.addKeyChange(entry.getKey(), entry.getValue());
                    }
                }

            } catch (Exception e) {
                if (watcher != null) {
                    watcher.close();
                }
                LOGGER.error(e.getLocalizedMessage());
            }
        }).start();
    }

    @Override
    public Map<String, String> getValueWithPrefix(String prefix) {
        try {
            GetResponse getResponse = client.getKVClient().get(ByteSequence.fromString(prefix), GetOption.newBuilder().withPrefix(ByteSequence.fromString(prefix)).build()).get();
            if (getResponse.getKvs().isEmpty()) {
                // key does not exist
                return null;
            }
            Map<String, String> keyValues = new HashMap<>();
            List<KeyValue> keyValueList = getResponse.getKvs();

            for(KeyValue keyValue: keyValueList) {
                keyValues.put(keyValue.getKey().toStringUtf8(), keyValue.getValue().toStringUtf8());
            }

            return keyValues;
        }catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage());
        }
        return null;
    }
}

