package org.zephyr.hbase.tool.replication;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.NamespaceExistException;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.replication.ReplicationAdmin;
import org.apache.hadoop.hbase.mapreduce.CopyTable;
import org.apache.hadoop.hbase.replication.ReplicationException;
import org.apache.hadoop.util.ToolRunner;

public class SyncHTable{
  private static final Log LOG = LogFactory.getLog(SyncHTable.class);
  
  private Admin srcAdmin;
  private Connection srcCon;
  private Configuration srcConf;
  private Admin dstAdmin;
  private Connection dstCon;
  private Configuration dstConf;
  private int numThreads = 3;
  private String peerId = "3";
  private Map<byte[], List<byte[]>> regionKeys;
  
  
  public void run (String[] args) throws Exception {
    parseArgs(args);
    
    srcConf = loadConf("source-hbase-site.xml");
    dstConf = loadConf("sink-hbase-site.xml");
    srcCon = ConnectionFactory.createConnection(srcConf);
    srcAdmin = srcCon.getAdmin();
    dstCon = ConnectionFactory.createConnection(dstConf);
    dstAdmin = dstCon.getAdmin();
    
    HTableDescriptor[] tables = srcAdmin.listTables();
    Executor executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch latch = new CountDownLatch(tables.length);
    
    addPeerInSource();
    
    Table meta = srcCon.getTable(TableName.META_TABLE_NAME); 
    Scan scan = new Scan();
    ResultScanner results = meta.getScanner(scan);
    
    for (Result res : results) {
      //TODO 
      //Initiate variable regionKeys
    }
    
    for (int i = 0; i < tables.length; i++) {
      executor.execute(new SyncTask(tables[i], latch));
    }
    
    latch.await();
    LOG.info("All synchronization task has finished.");
  }
  
  private void parseArgs(String[] args) throws Exception{
    for (int i = 0; i < args.length; i++) {
      String cmd = args[i];
      
      String threadsKey = "--threads=";
      if (cmd.startsWith(threadsKey)) {
        String numThreads_str = cmd.substring(threadsKey.length());
        try {
          numThreads = Integer.parseInt(numThreads_str);
        } catch (NumberFormatException nfe){
          LOG.error("Can't parse " + cmd + " ,parameter should be Integer.");
          throw nfe;
        }
        if (numThreads <= 0 || numThreads > 25) {
          numThreads = Math.min(25, numThreads);
          numThreads = Math.max(3, numThreads);
          LOG.warn(cmd + " out of range, fix to " + numThreads + ".");
        }
      }
      
      String peerIdKey = "--peer.id=";
      if (cmd.startsWith(peerIdKey)) {
        peerId = cmd.substring(peerIdKey.length());
        try {
          Short.parseShort(peerIdKey);
        } catch (NumberFormatException nfe) {
          LOG.error("Can't parse " + cmd + " ,parameter should be Short.");
          throw nfe;
        }
      }
    }
  }
  
  private boolean copyTableFromSource (long endTime, HTableDescriptor htd) {
    String[] args = new String[4];
    args[0] = "--endtime=" + endTime;
    args[1] = "--peer.adr=" + dstConf.get("hbase.zookeeper.quorum") + ":" 
        + dstConf.get("hbase.zookeeper.property.clientPort", "2181") + ":"
        + dstConf.get("zookeeper.znode.parent","/hbase");
    args[2] = "--families=";
    HColumnDescriptor[] families = htd.getColumnFamilies();
    for (int i =0; i < families.length; i++) {
      HColumnDescriptor hcd = families[i];
      if (i != 0) args[2] += ",";
      args[2] += hcd.getNameAsString();
    }
    args[3] = htd.getNameAsString();
      
    try {
      return ToolRunner.run(new CopyTable(srcConf), args) == 0;
    } catch (Exception e) {
      LOG.error("Copy " + htd.getNameAsString() + " has failed.", e);
    }
    return false;
  }
  
  byte[][] doPreSplit(HTableDescriptor htd) {
    byte[][] splitKeys;
    List<byte[]> rowKeys = regionKeys.get(htd.getNameAsString());
    int numRegions = Math.min(rowKeys.size(), 30);
    int interval = rowKeys.size() / numRegions;
    splitKeys = new byte[numRegions][];
    for (int i = interval - 1, j = 0; i < rowKeys.size(); i += interval) {
      splitKeys[j++] = rowKeys.get(i);
    }
    return splitKeys;
  }
  
  private boolean createTableInSink (HTableDescriptor srcHtd) {
    HTableDescriptor htd = new HTableDescriptor(srcHtd);
    
    // Close replication on backup table.
    for (HColumnDescriptor hcd : htd.getColumnFamilies()) {
      hcd.setScope(0);
      htd.modifyFamily(hcd);
    }
    
    try {
      dstAdmin.createTable(htd, doPreSplit(htd));
      return true;
    } catch (IOException e) {
      if (e instanceof TableExistsException) {
        return true;
      }
      LOG.error("Create table " + htd.getNameAsString() + " has failed.", e);
    }
    return false;
  }
  
  private boolean createNamespaceInSink (String namespace) {
    try {
      dstAdmin.createNamespace(NamespaceDescriptor.create(namespace).build());
      return true;
    } catch (IOException e) {
      if (e instanceof NamespaceExistException) {
        return true;
      }
      LOG.error("Create namespace " + namespace + " has failed.", e);
    }
    return false;
  }
  
  private boolean startReplication (HTableDescriptor htd) {
    HTableDescriptor backupHtd = new HTableDescriptor(htd);
    try {
      disableTable(htd.getTableName());      
      for (HColumnDescriptor hcd : htd.getColumnFamilies()) {
        hcd.setScope(1);
        htd.modifyFamily(hcd);
      }
      modifyTable(htd.getTableName(), htd);
      LOG.info("setup replication on table " + htd.getNameAsString());
    } catch (IOException e) {
      LOG.error("Start replication on table " + htd.getNameAsString() + " has failed.", e);
      // recover ColumnFamily
      try {
        if (!srcAdmin.isTableEnabled(htd.getTableName())) {
          modifyTable(htd.getTableName(), backupHtd);
        } else {
          // In this case, may fail to disable the table or somebody manually enable it.
          // We should check ColumnFamily.
          LOG.warn("recover " + htd.getNameAsString() + " ColumnFamily, but table is enabled.");
          
          htd = srcAdmin.getTableDescriptor(htd.getTableName());
          for (HColumnDescriptor curHcd : htd.getColumnFamilies()) {
            boolean hasSame = false;
            for (HColumnDescriptor bakHcd : htd.getColumnFamilies()) {
              if (curHcd.equals(bakHcd)) {
                hasSame = true;
                break;
              }
            }
            if (!hasSame) {
              // We may have changed partial ColumnFamilies.
              LOG.warn("force to recover " + htd.getNameAsString());
              disableTable(htd.getTableName());
              modifyTable(htd.getTableName(), backupHtd);
              break;
            }
          }
        }
      } catch (IOException ee) {
        LOG.error("recover " + htd.getNameAsString()
            + " ColumnFamily has failed. Backup HTableDescriptor: " + backupHtd, ee);
      }
    } finally {
      try {
        enableTable(htd.getTableName());
      } catch (IOException e) {
        LOG.error("enable table " + htd.getNameAsString() + "has failed.", e);
      }
    }
    
    return false;
  }
  
  private void enableTable (TableName tn) throws IOException {
    srcAdmin.enableTableAsync(tn);
    while (!srcAdmin.isTableEnabled(tn)) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOG.warn("sleep for enabling " + tn.getNameAsString() + ", but be interrupted", e);
      }
    }
  }
  
  private void disableTable (TableName tn) throws IOException {
    srcAdmin.disableTableAsync(tn);
    while (!srcAdmin.isTableDisabled(tn)) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOG.warn("sleep for disabling " + tn.getNameAsString() + ",but be interrupted", e);
      }
    }
  }
  
  private void modifyTable (TableName tn, HTableDescriptor htd) throws IOException {
    srcAdmin.modifyTable(tn, htd); 
    while (srcAdmin.getAlterStatus(tn).getFirst() > 0) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOG.warn("sleep for modifying " + tn.getNameAsString() + ",but be interrupted", e);
      }
    }
  }
  
  private void addPeerInSource () throws IOException, ReplicationException {
    ReplicationAdmin admin = new ReplicationAdmin(srcConf);
    String clusterId = dstConf.get("hbase.zookeeper.quorum") + ":" 
        + dstConf.get("hbase.zookeeper.property.clientPort", "2181") + ":"
        + dstConf.get("zookeeper.znode.parent","/hbase");
    admin.addPeer(peerId, clusterId);
  }
  
  private Configuration loadConf(String confName) throws IOException{
    InputStream in = SyncHTable.class.getClassLoader().getResourceAsStream(confName);
    if (in == null) {
      throw new IOException("configuration " + confName + " not found");
    }
    Configuration conf = new Configuration();
    conf.setClassLoader(SyncHTable.class.getClassLoader());
    conf.addResource("hbase-default.xml");
    conf.addResource(in);
    return conf;
  }
  
  public static void main (String[] args){
    try {
      new SyncHTable().run(args);
    } catch (Exception e) {
      LOG.error("Abort synchronization", e);
    }
  }
  
  
  class SyncTask implements Runnable {
    
    private HTableDescriptor htd;
    private CountDownLatch latch;
    
    SyncTask (HTableDescriptor htd, CountDownLatch latch) {
      this.htd = htd;
      this.latch = latch;
    }
    
    @Override
    public void run() {
      String namespace = htd.getTableName().getNameAsString();
      if (createNamespaceInSink(namespace) && createTableInSink(htd)) {
        if (startReplication(htd)) {
          // Delay 1 minute for preventing loss of data.
          // If copyTableEndPoint less than replicationStartPoint, 
          // we may loss data in range [copyTableEndPoint, replicationStartPoint)
          long replicationStartPoint = System.currentTimeMillis();
          long copyTableEndPoint = replicationStartPoint + 1000 * 60;
          LOG.info("table:" + htd.getNameAsString() + ", start replication at "
              + replicationStartPoint);
          
          copyTableFromSource(copyTableEndPoint, htd);
        }
      }
      latch.countDown();
    }
    
  }
  
}