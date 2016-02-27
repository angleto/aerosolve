package com.airbnb.aerosolve.training

import java.io.{StringReader, BufferedWriter, BufferedReader, StringWriter}

import com.airbnb.aerosolve.core.models.{ModelFactory, MlpModel}
import com.airbnb.aerosolve.core.Example
import com.typesafe.config.ConfigFactory
import org.apache.spark.SparkContext
import org.junit.Test
import org.slf4j.LoggerFactory
import org.junit.Assert._
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._

class MlpModelTrainerTest {
  val log = LoggerFactory.getLogger("MlpModelTrainerTest")
  def makeConfig(dropout : Double,
                 momentumT : Int,
                 maxNorm : Double,
                 loss : String,
                 extraArgs : String) : String = {
    """
      |identity_transform {
      |  transform : list
      |  transforms: []
      |}
      |model_config {
      |  num_bags : 3
      |  %s
      |  loss : %s
      |  rank_key : "$rank"
      |  rank_threshold : 0.0
      |  margin : 1.0
      |  learning_rate_init : 0.1
      |  learning_rate_decay : 0.95
      |  momentum_init : 0.5
      |  momentum_end : 0.9
      |  momentum_t : %d
      |  max_norm : %f
      |  weight_decay : 0.0
      |  weight_init_std : 0.01
      |  iterations : 100
      |  dropout : %f
      |  min_count : 0
      |  subsample : 0.1
      |  cache : "cache"
      |  context_transform : identity_transform
      |  item_transform : identity_transform
      |  combined_transform : identity_transform
      |  activations : ["sigmoid", "identity"]
      |  node_number : [3, 1]
      |  model_output : ""
      |}
    """.stripMargin.format(extraArgs, loss, momentumT, maxNorm, dropout)
  }

  // TODO (peng): add more tests and gradient checks
  @Test
  def testModelTrainerHingeNonLinear : Unit = {
    testMlpModelTrainer("hinge", 0.0, "", 0, 0, "poly")
  }

  @Test
  def testModelTrainerHingeLinear : Unit = {
    testMlpModelTrainer("hinge", 0.0, "", 0, 0, "linear")
  }

  def testMlpModelTrainer(loss : String,
                          dropout : Double,
                          extraArgs : String,
                          momentumT : Int,
                          maxNorm : Double,
                          exampleFunc: String = "poly") = {
    var sc = new SparkContext("local", "MlpModelTrainerTest")
    try {
      val (examples, label, numPos) = if (exampleFunc.equals("poly")) {
        TrainingTestHelper.makeClassificationExamples
      } else {
        TrainingTestHelper.makeLinearClassificationExamples
      }
      val config = ConfigFactory.parseString(makeConfig(dropout, momentumT, maxNorm, loss, extraArgs))
      val input = sc.parallelize(examples)
      val model = MlpModelTrainer.train(sc, input, config, "model_config")
      testClassificationModel(model, examples, label, numPos)
    } finally {
      sc.stop
      sc = null
      // To avoid Akka rebinding to the same port, since it doesn't unbind immediately on shutdown
      System.clearProperty("spark.master.port")
    }
  }
  def testClassificationModel(model: MlpModel,
                              examples: ArrayBuffer[Example],
                              label: ArrayBuffer[Double],
                              numPos: Int): Unit = {
    var numCorrect : Int = 0
    var i : Int = 0
    val labelArr = label.toArray
    for (ex <- examples) {
      val score = model.scoreItem(ex.example.get(0))
      if (score * labelArr(i) > 0) {
        numCorrect += 1
      }
      i += 1
    }
    val fracCorrect : Double = numCorrect * 1.0 / examples.length
    log.info("Num correct = %d, frac correct = %f, num pos = %d, num neg = %d"
      .format(numCorrect, fracCorrect, numPos, examples.length - numPos))
    assertTrue(fracCorrect > 0.6)

    val swriter = new StringWriter()
    val writer = new BufferedWriter(swriter)
    model.save(writer)
    writer.close()
    val str = swriter.toString()
    val sreader = new StringReader(str)
    val reader = new BufferedReader(sreader)
    log.info(str)
    val model2Opt = ModelFactory.createFromReader(reader)
    assertTrue(model2Opt.isPresent())
    val model2 = model2Opt.get()
    for (ex <- examples) {
      val score = model.scoreItem(ex.example.get(0))
      val score2 = model2.scoreItem(ex.example.get(0))
      assertEquals(score, score2, 0.01f)
    }
  }
}
