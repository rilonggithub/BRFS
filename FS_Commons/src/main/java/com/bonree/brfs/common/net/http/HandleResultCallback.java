package com.bonree.brfs.common.net.http;

/**
 * {@link MessageHandler}通知结果数据的接口
 * 
 * @author chen
 *
 */
public interface HandleResultCallback {
	void completed(HandleResult result);
}
