transmartApp
============

tranSMART is a knowledge management platform that enables scientists to develop
and refine research hypotheses by investigating correlations between genetic and
phenotypic data, and assessing their analytical results in the context of
published literature and other work.

Installation
------------

Some pre-requisites are required in order to run tranSMART. For development,
a copy of [grails][1] is needed in order to run the application. For production
or evaluation purposes, it is sufficient to download a pre-build WAR file, for
instance a snapshot from the [The Hyveʼs][2] or [tranSMART Foundationʼs][3]
CI/build servers, for snapshots of The Hyveʼs or tranSMART Foundationʼs GitHub
repositories, respectively. In order to run the WAR, an application server is
required. The only supported one is [Tomcat][4], either from the 6.x or 7.x
line, though it will most likely work on others.

In addition, a PostgreSQL database installed with the proper schema and data is
required. As of this moment, The Hyveʼs development branches require this to be
propared with the [transmart-data][5] repository. This project also handles
the required configuration, running the Solr instances and, for development
purposes, running an R server and installing sample data.

For details on how to install the tranSMART Foundationʼs versions, refer to
[their wiki][6].


  [1]: http://grails.org/
  [2]: https://ci.ctmtrait.nl/
  [3]: https://ci.transmartfoundation.org/
  [4]: http://tomcat.apache.org/
  [5]: https://github.com/thehyve/transmart-data
  [6]: https://wiki.transmartfoundation.org/
  
  
  

Big Data Configuration
------------

Pre-requisites 
---------
tranSMART research branch (https://github.com/transmart/transmartApp/tree/research) is required to be installed (not running) in order to deploy Bigdata tranSMART.

Installation
---------

1. Add configurations into Config.groovy
org.transmart.kv.enable = true
hbase.rootdir = "hdfs://{hadoop-master-hostname}:{hdfs-port}/hbase"
hbase.master = "{hbase-master-hostname}"
hbase.zookeeper.quorum = "{zookeeper-node-hostname}"
hbase.tmp.dir = "{hbase-data-dir}"
hbase.zookeeper.property.clientPort = "{zookeeper-port}"
fs.default.name = "hdfs://{hadoop-master-hostname}:{hdfs-port}"

2. Add configurations into BuildConfig.groovy
Please add grails.plugin.location.'hbase-core' = "{your-path}/hbase-core"
grails.plugin.location.'sendfile' = "{your-path}/sendfile-0.2"
grails.plugin.location.'rdc-rmodules' = "{your-path}/rdc-rmodules"

3. Run
grails run-app
