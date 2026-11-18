package com.jxgc.meattrace.chain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 链相关配置，对应 application-*.yml 的 {@code chain.*}。
 *
 * <p>实测环境：FISCO BCOS 3.17.0 + WeBASE 3.1.1，群组 {@code group0}。
 */
@ConfigurationProperties(prefix = "chain")
public class ChainProperties {

    private Webase webase = new Webase();
    private Rpc rpc = new Rpc();

    public Webase getWebase() {
        return webase;
    }

    public void setWebase(Webase webase) {
        this.webase = webase;
    }

    public Rpc getRpc() {
        return rpc;
    }

    public void setRpc(Rpc rpc) {
        this.rpc = rpc;
    }

    /** WeBASE-Front：负责编译、部署、写入（签名由 WeBASE-Sign 托管）。 */
    public static class Webase {
        private String baseUrl = "http://192.168.239.130:5002/WeBASE-Front";
        private String groupId = "group0";
        private String appId = "meattrace";
        private String signUserId = "meattraceadmin";
        private String userAddress = "0x0bd53242ca7ee7fb0ce57a3cb296b6d4b237dbe8";
        private String contractName = "TraceRegistry";
        private String contractPath = "trace";
        private String contractAddress = "";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 8000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getSignUserId() {
            return signUserId;
        }

        public void setSignUserId(String signUserId) {
            this.signUserId = signUserId;
        }

        public String getUserAddress() {
            return userAddress;
        }

        public void setUserAddress(String userAddress) {
            this.userAddress = userAddress;
        }

        public String getContractName() {
            return contractName;
        }

        public void setContractName(String contractName) {
            this.contractName = contractName;
        }

        public String getContractPath() {
            return contractPath;
        }

        public void setContractPath(String contractPath) {
            this.contractPath = contractPath;
        }

        public String getContractAddress() {
            return contractAddress;
        }

        public void setContractAddress(String contractAddress) {
            this.contractAddress = contractAddress;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    /** 节点标准 JSON-RPC：只读查询与回执，免交易。 */
    public static class Rpc {
        private String url = "http://192.168.239.130:8545";
        private int timeoutMs = 5000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
