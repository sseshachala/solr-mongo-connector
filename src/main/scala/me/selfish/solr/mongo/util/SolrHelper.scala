/**
 * Copyright 2013 SelfishInc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.selfish.solr.mongo.util


import scala.collection.JavaConversions._
import org.apache.solr.client.solrj.impl.{ConcurrentUpdateSolrServer, HttpSolrServer}
import scala.collection.{Iterable, Map}
import org.apache.solr.common.{SolrInputField, SolrInputDocument}
import TimestampHelper.BSONToLong
import org.apache.solr.client.solrj.impl.HttpSolrServer.RemoteSolrException
import com.typesafe.scalalogging.slf4j.Logging
import com.mongodb.DBObject
import org.apache.solr.client.solrj.SolrQuery
import me.selfish.solr.mongo.{MongoUpdateOperation, MongoInsertOperation, MongoOpLogEntry, MongoConversions}
import org.bson.types.BSONTimestamp


object SolrHelper extends Logging {


  val solrServer = new ConcurrentUpdateSolrServer(Config.solrUri, 100, 4)


  //used to work with mongo $set operation on arrays
  private val r = """(.*)\.(\d+)$""".r

  import MongoConversions._
  def getSolrInputDocument(operation: MongoOpLogEntry): Option[SolrInputDocument] = {

   operation match {
     case update: MongoUpdateOperation => {
       inputDocFromUpdate(update)
     }
     case insert: MongoInsertOperation => {
       inputDocFromInsert(insert)
     }
     case o =>
       throw new UnsupportedOperationException(s"Operation $o doesn't supported")
   }
  }


  private def inputDocFromUpdate(update: MongoUpdateOperation): Option[SolrInputDocument] = {
    //check operation
    if(!update.document.containsField("_id")){
      update.document.keys foreach { k =>
        k match {
          case "$set" =>
          case unknownKey => {
            logger.warn(s"unsupported operation $unknownKey in update operation.\n" +
              s"update doc: $update")
          }
        }
      }
    }


    if(update.document.containsField("_id")){
      Some(getSolrInputDocument(convert(update.document), update.namespace, update.timestamp))
    }
    else if(update.document.containsField("$set")){
      val doc = new SolrInputDocument()
      val setDoc = update.document.get("$set").asInstanceOf[DBObject]

      setDoc.keySet() foreach {k =>
//        logger.debug(s"set ops. key: $k")

        //check for arrays
        val (key, ops) = r.findFirstMatchIn(k) match {
          case Some(s) => {
            val r(name, _) = k
            (name, "add")
          }
          case None => (k, "set")
        }

        val v = convert(setDoc.get(k), key)
        v.foreach {
          case (k, v) =>
            v match {
              case t: Iterable[_] => {
                t.foreach(e => {
                  val m = new java.util.HashMap[String, Any]()
                  m.put(ops, e)
                  doc.addField(k, m)
                })
              }
              case t => {
                val m = new java.util.HashMap[String, Any]()
                m.put(ops, t)
                doc.addField(k, m)
              }
            }
        }
      }
      doc.addField("_id", update.documentID.get("_id"))
      //do not remove this fields
      doc.addField("_ts", update.timestamp.toLong)
      doc.addField("ns", update.namespace)

      Some(doc)
    } else {
      None
    }
  }


  private def inputDocFromInsert(insert: MongoInsertOperation): Option[SolrInputDocument] = {
    Some(getSolrInputDocument(convert(insert.document), insert.namespace, insert.timestamp))
  }


  def getSolrInputDocument(map: Map[String, Any], ns: String, ts: Long): SolrInputDocument = {
    val doc = new SolrInputDocument()
    map.foreach {
      case (k, v) =>
        v match {
          case t: Iterable[_] => t.foreach(e => doc.addField(k, e))
          case t => {
            doc.addField(k, t)
          }
        }
    }
    doc.addField("ns", ns)
    doc.addField("_ts", ts)
    doc
  }


  def addDocsToSolr(docs: Iterable[SolrInputDocument], commit: Boolean = true): Int = {
    if (!docs.isEmpty) {
      val response = solrServer.add(docs)
      if (commit)
        solrServer.commit().getStatus
      else
        response.getStatus
    }
    else 0
  }


  def deleteDocsFromSolr(docs: List[String], commit: Boolean = true): Int = {
    if (!docs.isEmpty) {
      val response = solrServer.deleteById(docs)
      if (commit)
        solrServer.commit().getStatus
      else
        response.getStatus
    }
    else 0
  }

  def dropCollectionFromSolr(query: String): Int = {
    val response = solrServer.deleteByQuery(query)
    //TODO: should we check response status?
    solrServer.commit().getStatus
  }

  def getLastTimestamp(collections: List[String]): Option[BSONTimestamp] = {
    import me.selfish.solr.mongo.util.TimestampHelper._

    val query = new SolrQuery("ns:"+collections.mkString("("," OR ",")"))
    query.addSort("_ts", SolrQuery.ORDER.desc)
    val docs = solrServer.query( query ).getResults()
    docs.getNumFound match {
      case num:Long if num > 0 => Some( toBSONTimestamp(docs.get(0).getFieldValue("_ts").toString.toLong) )
      case _ => None
    }
  }
}
