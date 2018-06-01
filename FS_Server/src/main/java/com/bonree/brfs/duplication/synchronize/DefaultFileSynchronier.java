package com.bonree.brfs.duplication.synchronize;

import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bonree.brfs.common.service.Service;
import com.bonree.brfs.common.service.ServiceManager;
import com.bonree.brfs.common.service.ServiceStateListener;
import com.bonree.brfs.common.utils.BitSetUtils;
import com.bonree.brfs.common.utils.PooledThreadFactory;
import com.bonree.brfs.configuration.Configuration;
import com.bonree.brfs.configuration.ServerConfig;
import com.bonree.brfs.disknode.client.AvailableSequenceInfo;
import com.bonree.brfs.disknode.client.DiskNodeClient;
import com.bonree.brfs.disknode.client.RecoverInfo;
import com.bonree.brfs.duplication.DuplicationEnvironment;
import com.bonree.brfs.duplication.coordinator.DuplicateNode;
import com.bonree.brfs.duplication.coordinator.FileNode;
import com.bonree.brfs.duplication.coordinator.FilePathBuilder;
import com.bonree.brfs.duplication.datastream.connection.DiskNodeConnection;
import com.bonree.brfs.duplication.datastream.connection.DiskNodeConnectionPool;
import com.bonree.brfs.server.identification.ServerIDManager;

public class DefaultFileSynchronier implements FileSynchronizer {
	private static final Logger LOG = LoggerFactory.getLogger(DefaultFileSynchronier.class);
	
	private static final int DEFAULT_THREAD_NUM = 1;
	private ExecutorService threadPool;
	
	private DiskNodeConnectionPool connectionPool;
	
	private ServerIDManager idManager;
	
	private ServiceManager serviceManager;
	private ServiceStateListener serviceStateListener;
	
	private DelayTaskActivator taskActivator;
	private List<FileSynchronizeTask> delayedFileList = new ArrayList<FileSynchronizeTask>();
	
	private static final String ERROR_FILE = "error_records";
	private SynchronierErrorRecorder errorRecorder;
	
	public DefaultFileSynchronier(DiskNodeConnectionPool connectionPool, ServiceManager serviceManager, ServerIDManager idManager) {
		this(DEFAULT_THREAD_NUM, connectionPool, serviceManager, idManager);
	}
	
	public DefaultFileSynchronier(int threadNum, DiskNodeConnectionPool connectionPool, ServiceManager serviceManager, ServerIDManager idManager) {
		this.connectionPool = connectionPool;
		this.idManager = idManager;
		this.serviceManager = serviceManager;
		this.serviceStateListener = new DiskNodeServiceStateListener();
		this.taskActivator = new DelayTaskActivator();
		this.errorRecorder = new SynchronierErrorRecorder(new File(Configuration.getInstance().getProperty(Configuration.PATH_LOGS),
				ERROR_FILE));
		this.threadPool = new ThreadPoolExecutor(threadNum, threadNum,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new PooledThreadFactory("file_synchronize"));
	}

	@Override
	public void start() throws Exception {
		taskActivator.start();
		serviceManager.addServiceStateListener(ServerConfig.DEFAULT_DISK_NODE_SERVICE_GROUP, serviceStateListener);
	}

	@Override
	public void stop() throws Exception {
		serviceManager.removeServiceStateListener(ServerConfig.DEFAULT_DISK_NODE_SERVICE_GROUP, serviceStateListener);
		taskActivator.quit();
		taskActivator.interrupt();
		threadPool.shutdown();
	}

	@Override
	public void synchronize(FileNode fileNode, FileSynchronizeCallback listener) {
		threadPool.submit(new FileSynchronizeTask(fileNode, listener));
	}
	
	private void addDelayedTask(FileSynchronizeTask task) {
		synchronized (delayedFileList) {
			LOG.info("add to delayed task list for filnode[{}]", task.fileNode().getName());
			delayedFileList.add(task);
		}
	}
	
	public class FileSynchronizeTask implements Runnable {
		private final FileNode target;
		private final FileSynchronizeCallback callback;
		
		public FileSynchronizeTask(FileNode fileNode, FileSynchronizeCallback listener) {
			this.target = fileNode;
			this.callback = listener;
		}
		
		public FileNode fileNode() {
			return this.target;
		}
		
		public FileSynchronizeCallback callback() {
			return this.callback;
		}

		@Override
		public void run() {
			LOG.info("start synchronize file[{}]", target.getName());
			
			/**
			 * 文件之间的内容协调是通过写入文件的序列号实现的，只要当前存活的磁盘节点包含
			 * 所有写入序列号就能保证文件的完整性
			 */
			List<DuplicateNode> nodeList = getActiveDuplicateNodes(target);
			
			List<DuplicateNodeSequence> seqNumberList = getAllDuplicateNodeSequence(nodeList);
			
			if(seqNumberList.size() != nodeList.size()) {
				//不相等的情况不能再继续恢复了，我们选择等待
				LOG.info("can not get all sequences of file[{}]", target.getName());
				addDelayedTask(this);
				return;
			}
			
			if(seqNumberList.isEmpty()) {
				LOG.error("No available duplicate node to found for file[{}]", target.getName());
				addDelayedTask(this);
				return;
			}
			
			BitSet[] sets = new BitSet[seqNumberList.size()];
			for(int i = 0; i < sets.length; i++) {
				sets[i] = seqNumberList.get(i).getSequenceNumbers();
			}
			
			//每个副本节点的序列号的交集
			BitSet union = BitSetUtils.union(sets);
			//每个副本节点都有的序列号
			BitSet intersection = BitSetUtils.intersect(sets);
			
			/**
			 * 查看所有节点的序列号是否覆盖了[0, maxSeq]之间的所有数值
			 */
			LOG.info("synchronize Check report union[{}], itersection[{}]", union.cardinality(), intersection.cardinality());
			if(union.nextSetBit(union.cardinality()) == -1) {
				//当前存活的所有节点包含了此文件的所有信息，可以进行文件内容同步
				if(intersection.cardinality() == union.cardinality()) {
					//如果交集和并集的数量一样，说明没有文件数据缺失
					LOG.info("file[{}] is ok!", target.getName());
					callback.complete(target);
					return;
				}
				
				doSynchronize(seqNumberList, union, intersection);
			} else {
				//存在文件内容丢失，怎么办，怎么办
				LOG.info("file[{}] is error!", target.getName());
				errorRecorder.writeErrorFile(target);
				callback.error(new Exception("Content of the file[" + target.getName() + "] is deficient!!"));
			}
			
			LOG.info("End synchronize file[{}]", target.getName());
		}
		
		private List<DuplicateNode> getActiveDuplicateNodes(FileNode fileNode) {
			List<DuplicateNode> nodeList = new ArrayList<DuplicateNode>();
			for(DuplicateNode node : fileNode.getDuplicateNodes()) {
				if(node.getGroup().equals(DuplicationEnvironment.VIRTUAL_SERVICE_GROUP)) {
					LOG.info("virtual server[{}, {}] is ignored to get sequences", node.getGroup(), node.getId());
					continue;
				}
				
				nodeList.add(node);
			}
			
			return nodeList;
		}
		
		private List<DuplicateNodeSequence> getAllDuplicateNodeSequence(List<DuplicateNode> nodeList) {
			List<DuplicateNodeSequence> seqNumberList = new ArrayList<DuplicateNodeSequence>();
			
			for(DuplicateNode node : nodeList) {
				DiskNodeConnection connection = connectionPool.getConnection(node);
				if(connection == null || connection.getClient() == null) {
					LOG.error("duplication node[{}, {}] of [{}] is not available, that's maybe a trouble!", node.getGroup(), node.getId(), target.getName());
					continue;
				}
				
				String serverId = idManager.getOtherSecondID(node.getId(), target.getStorageId());
				String filePath =FilePathBuilder.buildFilePath(target.getStorageName(), serverId, target.getCreateTime(), target.getName());
				LOG.info("checking---{}", filePath);
				BitSet seqNumbers = connection.getClient().getWritingSequence(filePath);
				
				if(seqNumbers == null) {
					LOG.error("duplication node[{}, {}] of [{}] can not get file sequences, that's maybe a trouble!", node.getGroup(), node.getId(), target.getName());
					continue;
				}
				
				LOG.info("server{} -- {}", node.getId(), seqNumbers.cardinality());
				
				DuplicateNodeSequence nodeSequence = new DuplicateNodeSequence();
				nodeSequence.setNode(node);
				nodeSequence.setSequenceNumbers(seqNumbers);
				
				seqNumberList.add(nodeSequence);
			}
			
			return seqNumberList;
		}
		
		private void doSynchronize(List<DuplicateNodeSequence> seqNumberList, BitSet union, BitSet intersection) {
			List<AvailableSequenceInfo> infos = new ArrayList<AvailableSequenceInfo>();
			for(DuplicateNodeSequence sequence : seqNumberList) {
				BitSet iHave = BitSetUtils.minus(sequence.getSequenceNumbers(), intersection);
				LOG.info("Duplicate node[{}] has available seq num[{}]", sequence.getNode(), iHave.cardinality());
				
				AvailableSequenceInfo info = new AvailableSequenceInfo();
				info.setServiceGroup(sequence.getNode().getGroup());
				info.setServiceId(sequence.getNode().getId());
				info.setAvailableSequence(iHave);
				
				String serverId = idManager.getOtherSecondID(sequence.getNode().getId(), target.getStorageId());
				info.setFilePath(FilePathBuilder.buildFilePath(target.getStorageName(), serverId, target.getCreateTime(), target.getName()));
				
				infos.add(info);
			}
			
			//这部分我们称呼它为“补漏”
			RecoverInfo recoverInfo = new RecoverInfo();
			recoverInfo.setMaxSeq(union.cardinality() - 1);
			recoverInfo.setInfoList(infos);
			for(DuplicateNodeSequence sequence : seqNumberList) {
				BitSet lack = BitSetUtils.minus(union, sequence.getSequenceNumbers());
				if(lack.isEmpty()) {
					//没有空缺内容，不需要恢复
					continue;
				}
				
				DiskNodeConnection connection = connectionPool.getConnection(sequence.getNode());
				if(connection == null || connection.getClient() == null) {
					LOG.error("can not recover file[{}], because of lack of connection to duplication node[{}]", target.getName(), sequence.getNode());
					continue;
				}
				
				DiskNodeClient client = connection.getClient();
				String serverId = idManager.getOtherSecondID(sequence.getNode().getId(), target.getStorageId());
				LOG.info("start synchronize file[{}] at duplicate node[{}]", target.getName(), sequence.getNode());
				if(!client.recover(FilePathBuilder.buildFilePath(target.getStorageName(), serverId, target.getCreateTime(), target.getName()), recoverInfo)) {
					addDelayedTask(this);
					LOG.error("can not synchronize file[{}] at duplicate node[{}]", target.getName(), sequence.getNode());
					return;
				}
				LOG.info("file synchronizition completed successfully!");
			}
			
			callback.complete(target);
		}
	}
	
	private class DiskNodeServiceStateListener implements ServiceStateListener {

		@Override
		public void serviceAdded(Service service) {
			LOG.info("Disk Node[{}] added", service);
			taskActivator.putService(service);
		}

		@Override
		public void serviceRemoved(Service service) {
			LOG.info("Disk Node[{}] removed", service);
		}
		
	}
	
	private class DelayTaskActivator extends Thread {
		private BlockingQueue<Service> serviceQueue = new LinkedBlockingQueue<Service>();
		
		private volatile boolean isQuit = false;
		
		public void putService(Service service) {
			try {
				serviceQueue.put(service);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		public void quit() {
			isQuit = true;
		}

		@Override
		public void run() {
			while(!isQuit) {
				try {
					Service service = serviceQueue.take();
					if(service == null) {
						continue;
					}
					LOG.info("Service[{}] found to active tasks", service);
					
					FileSynchronizeTask[] tasks;
					synchronized (delayedFileList) {
						tasks = new FileSynchronizeTask[delayedFileList.size()];
						delayedFileList.toArray(tasks);
						delayedFileList.clear();
					}
					
					for(FileSynchronizeTask task : tasks) {
						FileNode fileNode = task.fileNode();
						
						boolean submitted = false;
						for(DuplicateNode node : fileNode.getDuplicateNodes()) {
							if(node.getGroup().equals(service.getServiceGroup())
									&& node.getId().equals(service.getServiceId())) {
								LOG.info("restart task for fileNode[{}]", fileNode.getName());
								threadPool.submit(task);
								submitted = true;
								continue;
							}
						}
						
						if(submitted) {
							continue;
						}
						
						addDelayedTask(task);
						LOG.info("skipped synchronize file node[{}]", fileNode.getName());
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		
	}

}