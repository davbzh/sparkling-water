/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.spark.h2o.converters

import org.apache.spark.TaskContext
import org.apache.spark.h2o._
import org.apache.spark.h2o.utils.{NodeDesc, ReflectionUtils}
import org.apache.spark.internal.Logging
import water.fvec.H2OFrame
import water.{ExternalFrameUtils, Key}

import scala.collection.immutable
import scala.language.implicitConversions
import scala.reflect.runtime.universe._

private[converters] object PrimitiveRDDConverter extends Logging with ConverterUtils{

  def toH2OFrame[T: TypeTag](hc: H2OContext, rdd: RDD[T], frameKeyName: Option[String]): H2OFrame = {
    import ReflectionUtils._

    val keyName = frameKeyName.getOrElse("frame_rdd_" + rdd.id + Key.rand())

    val fnames = Array[String]("values")

    // in case of internal backend, store regular vector types
    // otherwise for external backend store expected types
    val expectedTypes = if(hc.getConf.runsInInternalClusterMode){
      Array[Byte](vecTypeOf[T])
    }else{
      val clazz = ReflectionUtils.javaClassOf[T]
      ExternalFrameUtils.prepareExpectedTypes(Array[Class[_]](clazz))
    }

    convert[T](hc, rdd, keyName, fnames, expectedTypes, perPrimitiveRDDPartition())
  }


  /**
    *
    * @param keyName key of the frame
    * @param vecTypes h2o vec types
    * @param uploadPlan if external backend is used, then it is a plan which assigns each partition h2o
    *                   node where the data from that partition will be uploaded, otherwise is Node
    * @param context spark task context
    * @param it iterator over data in the partition
    * @tparam T type of data inside the RDD
    * @return pair (partition ID, number of rows in this partition)
    */
  private[this]
  def perPrimitiveRDDPartition[T]() // extra arguments for this transformation
                                 (keyName: String, vecTypes: Array[Byte], uploadPlan: Option[immutable.Map[Int, NodeDesc]]) // general arguments
                                 (context: TaskContext, it: Iterator[T]): (Int, Long) = { // arguments and return types needed for spark's runJob input

    val asArr = it.toArray[Any] // need to buffer the iterator in order to get its length
    val con = ConverterUtils.getWriteConverterContext(uploadPlan, context.partitionId())
    con.createChunks(keyName, vecTypes, context.partitionId(), asArr.length)

    asArr.foreach {
      case n: Boolean => con.put(0, n)
      case n: Byte => con.put(0, n)
      case n: Char => con.put(0, n)
      case n: Short => con.put(0, n)
      case n: Int => con.put(0, n)
      case n: Long => con.put(0, n)
      case n: Float => con.put(0, n)
      case n: Double => con.put(0, n)
      case n: String => con.put(0, n)
      case n: java.sql.Timestamp => con.put(0, n)
      case _ => con.putNA(0)
    }
    //Compress & write data in partitions to H2O Chunks
    con.closeChunks()

    // Return Partition number and number of rows in this partition
    (context.partitionId, asArr.length)
  }

}
