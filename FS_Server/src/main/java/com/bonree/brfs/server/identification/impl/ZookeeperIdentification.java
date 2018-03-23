package com.bonree.brfs.server.identification.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bonree.brfs.common.utils.StringUtils;
import com.bonree.brfs.common.zookeeper.curator.CuratorClient;
import com.bonree.brfs.common.zookeeper.curator.locking.CuratorLocksClient;
import com.bonree.brfs.common.zookeeper.curator.locking.Executor;
import com.bonree.brfs.server.identification.Identification;

/*******************************************************************************
 * 版权信息：博睿宏远科技发展有限公司
 * Copyright: Copyright (c) 2007博睿宏远科技发展有限公司,Inc.All Rights Reserved.
 * 
 * @date 2018年3月19日 上午11:49:32
 * @Author: <a href=mailto:weizheng@bonree.com>魏征</a>
 * @Description: 使用zookeeper实现获取单副本服务标识，多副本服务标识，虚拟服务标识
 * 为了安全性，此处的方法，不需要太高的效率，故使用synchronized字段,该实例为单例模式
 ******************************************************************************/
public class ZookeeperIdentification implements Identification {

    private static final Logger LOG = LoggerFactory.getLogger(ZookeeperIdentification.class);

    private final String basePath;

    private CuratorClient client;

    private final static String SINGLE_NODE = "single";

    private final static String MULTI_NODE = "multi";

    private final static String VIRTUAL_NODE = "virtual";

    private final static String LOCKS_PATH_PART = "locks";

    private final static String VIRTUAL_SERVER = "virtualServers";

    private final static String SEPARATOR = "/";

    private final String lockPath;

    private class ZookeeperIdentificationGen implements Executor<String> {

        private final String dataNode;
        private final int type;

        public ZookeeperIdentificationGen(String dataNode, int type) {
            this.dataNode = dataNode;
            this.type = type;
        }

        @Override
        public String execute(CuratorClient client) {
            if (type == Identification.VIRTUAL) {
                String virtualServerId = getServersId(client);
                String virtualServerNode = basePath + SEPARATOR + VIRTUAL_SERVER + SEPARATOR + virtualServerId;
                client.createPersistent(virtualServerNode, false);
                return virtualServerId;
            } else {
                return getServersId(client);
            }

        }

        private String getServersId(CuratorClient client) {
            if (!client.checkExists(dataNode)) {
                client.createPersistent(dataNode, true, "0".getBytes());
            }
            byte[] bytes = client.getData(dataNode);
            String serverId = new String(bytes);
            int tmp = Integer.parseInt(new String(bytes)) + 1;
            client.setData(dataNode, String.valueOf(tmp).getBytes());
            return serverId;
        }

    }

    private ZookeeperIdentification(String zkUrl, String basePath) {
        client = CuratorClient.getClientInstance(zkUrl);
        this.basePath = StringUtils.trimBasePath(basePath);
        this.lockPath = basePath + SEPARATOR + LOCKS_PATH_PART;
        checkPathAndCreate(lockPath);
        checkPathAndCreate(basePath + SEPARATOR + VIRTUAL_SERVER);
    }

    public static volatile ZookeeperIdentification identificationServer = null;

    public String getBasePath() {
        return basePath;
    }

    private void checkPathAndCreate(String path) {
        if (!client.checkExists(path)) {
            client.createPersistent(path, true);
        }
    }

    public static Identification getIdentificationServer(final String zkUrl, final String basePath) {
        if (identificationServer == null) {
            synchronized (ZookeeperIdentification.class) {
                if (identificationServer == null) {
                    identificationServer = new ZookeeperIdentification(zkUrl, basePath);
                }
            }
        }
        return identificationServer;
    }

    @Override
    public synchronized String getSingleIdentification() {
        String serverId = null;
        String singleNode = basePath + SEPARATOR + SINGLE_NODE;
        ZookeeperIdentificationGen genExecutor = new ZookeeperIdentificationGen(singleNode, SINGLE);
        CuratorLocksClient<String> lockClient = new CuratorLocksClient<String>(client, lockPath, genExecutor, "genSingleIdentification");
        try {
            serverId = SINGLE + lockClient.execute();
        } catch (Exception e) {
            LOG.error("getSingleIdentification error!", e);
        }
        return serverId;
    }

    @Override
    public synchronized String getMultiIndentification() {
        String serverId = null;
        String multiNode = basePath + SEPARATOR + MULTI_NODE;
        ZookeeperIdentificationGen genExecutor = new ZookeeperIdentificationGen(multiNode, MULTI);
        CuratorLocksClient<String> lockClient = new CuratorLocksClient<String>(client, lockPath, genExecutor, "genMultiIdentification");
        try {
            serverId = MULTI + lockClient.execute();
        } catch (Exception e) {
            LOG.error("getMultiIndentification error!", e);
        }
        return serverId;
    }

    @Override
    public synchronized String getVirtureIdentification() {
        String serverId = null;
        String virtualNode = basePath + SEPARATOR + VIRTUAL_NODE;
        ZookeeperIdentificationGen genExecutor = new ZookeeperIdentificationGen(virtualNode, VIRTUAL);
        CuratorLocksClient<String> lockClient = new CuratorLocksClient<String>(client, lockPath, genExecutor, "genVirtualIdentification");
        try {
            serverId = VIRTUAL + lockClient.execute();
        } catch (Exception e) {
            LOG.error("getVirtureIdentification error!", e);
        }
        return serverId;
    }

    @Override
    public synchronized List<String> loadVirtualIdentification() {
        List<String> virtualIds = client.getChildren(basePath + SEPARATOR + VIRTUAL_SERVER);
        return virtualIds;
    }
}