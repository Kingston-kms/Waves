package com.wavesplatform.it.sync.grpc

import com.wavesplatform.common.utils.{Base64, EitherExt2}
import com.wavesplatform.it.NTPTime
import com.wavesplatform.it.api.SyncGrpcApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.sync.transactions.ExchangeTransactionSuite.mkExchange
import com.wavesplatform.it.sync.transactions.PriorityTransaction
import com.wavesplatform.it.util._
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions, Recipient}
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxVersion
import com.wavesplatform.transaction.assets.IssueTransaction
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order}
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.utils._
import io.grpc.Status.Code

import scala.collection.immutable

class ExchangeTransactionGrpcSuite extends GrpcBaseTransactionSuite with NTPTime with PriorityTransaction {
  import grpcApi._

  val transactionV1versions: (TxVersion, TxVersion, TxVersion) = (1: Byte, 1: Byte, 1: Byte)
  val transactionV2versions: immutable.Seq[(TxVersion, TxVersion, TxVersion)] = for {
    o1ver <- 1 to 3
    o2ver <- 1 to 3
    txVer <- 2 to 3
  } yield (o1ver.toByte, o2ver.toByte, txVer.toByte)

  val (buyer, buyerAddress)     = (firstAcc, firstAddress)
  val (seller, sellerAddress)   = (secondAcc, secondAddress)
  val (matcher, matcherAddress) = (thirdAcc, thirdAddress)

  val versions: immutable.Seq[(TxVersion, TxVersion, TxVersion)] = transactionV1versions +: transactionV2versions

  test("exchange tx with orders v1,v2") {
    val exchAsset =
      sender.broadcastIssue(buyer, Base64.encode("exchAsset".utf8Bytes), someAssetAmount, 8, reissuable = true, 1.waves, waitForTx = true)
    val exchAssetId        = PBTransactions.vanilla(exchAsset).explicitGet().id().toString
    val price              = 500000L
    val amount             = 40000000L
    val priceAssetSpending = amount * price / 100000000L
    val pair               = AssetPair.createAssetPair("WAVES", exchAssetId).get
    for ((o1ver, o2ver, tver) <- versions) {
      val ts                       = ntpTime.correctedTime()
      val expirationTimestamp      = ts + Order.MaxLiveTime
      val buy                      = Order.buy(o1ver, buyer, matcher, pair, amount, price, ts, expirationTimestamp, matcherFee)
      val sell                     = Order.sell(o2ver, seller, matcher, pair, amount, price, ts, expirationTimestamp, matcherFee)
      val buyerWavesBalanceBefore  = sender.wavesBalance(buyerAddress).available
      val sellerWavesBalanceBefore = sender.wavesBalance(sellerAddress).available
      val buyerAssetBalanceBefore  = sender.assetsBalance(buyerAddress, Seq(exchAssetId)).getOrElse(exchAssetId, 0L)
      val sellerAssetBalanceBefore = sender.assetsBalance(sellerAddress, Seq(exchAssetId)).getOrElse(exchAssetId, 0L)

      sender.exchange(matcher, buy, sell, amount, price, matcherFee, matcherFee, matcherFee, ts, tver, waitForTx = true)

      sender.wavesBalance(buyerAddress).available shouldBe buyerWavesBalanceBefore + amount - matcherFee
      sender.wavesBalance(sellerAddress).available shouldBe sellerWavesBalanceBefore - amount - matcherFee
      sender.assetsBalance(buyerAddress, Seq(exchAssetId))(exchAssetId) shouldBe buyerAssetBalanceBefore - priceAssetSpending
      sender.assetsBalance(sellerAddress, Seq(exchAssetId))(exchAssetId) shouldBe sellerAssetBalanceBefore + priceAssetSpending
    }
  }

  test("exchange tx with orders v3") {
    val feeAsset           = sender.broadcastIssue(buyer, "feeAsset", someAssetAmount, 8, reissuable = true, 1.waves, waitForTx = true)
    val feeAssetId         = PBTransactions.vanilla(feeAsset).explicitGet().id()
    val price              = 500000L
    val amount             = 40000000L
    val priceAssetSpending = price * amount / 100000000L

    sender.broadcastTransfer(
      buyer,
      Recipient().withPublicKeyHash(sellerAddress),
      someAssetAmount / 2,
      minFee,
      assetId = feeAssetId.toString,
      waitForTx = true
    )

    for ((o1ver, o2ver, matcherFeeOrder1, matcherFeeOrder2, buyerWavesDelta, sellerWavesDelta, buyerAssetDelta, sellerAssetDelta) <- Seq(
           (1: Byte, 3: Byte, Waves, IssuedAsset(feeAssetId), amount - matcherFee, -amount, -priceAssetSpending, priceAssetSpending - matcherFee),
           (1: Byte, 3: Byte, Waves, Waves, amount - matcherFee, -amount - matcherFee, -priceAssetSpending, priceAssetSpending),
           (2: Byte, 3: Byte, Waves, IssuedAsset(feeAssetId), amount - matcherFee, -amount, -priceAssetSpending, priceAssetSpending - matcherFee),
           (3: Byte, 1: Byte, IssuedAsset(feeAssetId), Waves, amount, -amount - matcherFee, -priceAssetSpending - matcherFee, priceAssetSpending),
           (2: Byte, 3: Byte, Waves, Waves, amount - matcherFee, -amount - matcherFee, -priceAssetSpending, priceAssetSpending),
           (3: Byte, 2: Byte, IssuedAsset(feeAssetId), Waves, amount, -amount - matcherFee, -priceAssetSpending - matcherFee, priceAssetSpending)
         )) {

      val buyerWavesBalanceBefore  = sender.wavesBalance(buyerAddress).available
      val sellerWavesBalanceBefore = sender.wavesBalance(sellerAddress).available
      val buyerAssetBalanceBefore  = sender.assetsBalance(buyerAddress, Seq(feeAssetId.toString)).getOrElse(feeAssetId.toString, 0L)
      val sellerAssetBalanceBefore = sender.assetsBalance(sellerAddress, Seq(feeAssetId.toString)).getOrElse(feeAssetId.toString, 0L)

      val ts                  = ntpTime.correctedTime()
      val expirationTimestamp = ts + Order.MaxLiveTime
      val assetPair           = AssetPair.createAssetPair("WAVES", feeAssetId.toString).get
      val buy                 = Order.buy(o1ver, buyer, matcher, assetPair, amount, price, ts, expirationTimestamp, matcherFee, matcherFeeOrder1)
      val sell                = Order.sell(o2ver, seller, matcher, assetPair, amount, price, ts, expirationTimestamp, matcherFee, matcherFeeOrder2)

      sender.exchange(matcher, sell, buy, amount, price, matcherFee, matcherFee, matcherFee, ts, 3, waitForTx = true)

      sender.wavesBalance(buyerAddress).available shouldBe (buyerWavesBalanceBefore + buyerWavesDelta)
      sender.wavesBalance(sellerAddress).available shouldBe (sellerWavesBalanceBefore + sellerWavesDelta)
      sender.assetsBalance(buyerAddress, Seq(feeAssetId.toString))(feeAssetId.toString) shouldBe (buyerAssetBalanceBefore + buyerAssetDelta)
      sender.assetsBalance(sellerAddress, Seq(feeAssetId.toString))(feeAssetId.toString) shouldBe (sellerAssetBalanceBefore + sellerAssetDelta)
    }
  }

  test("cannot exchange non-issued assets") {
    val exchAsset: IssueTransaction = IssueTransaction(
      TxVersion.V1,
      sender.privateKey,
      "myasset".utf8Bytes,
      "my asset description".utf8Bytes,
      quantity = someAssetAmount,
      decimals = 2,
      reissuable = true,
      script = None,
      fee = 1.waves,
      timestamp = System.currentTimeMillis()
    ).signWith(sender.privateKey)
    for ((o1ver, o2ver, tver) <- versions) {

      val assetId             = exchAsset.id().toString
      val ts                  = ntpTime.correctedTime()
      val expirationTimestamp = ts + Order.MaxLiveTime
      val price               = 2 * Order.PriceConstant
      val amount              = 1
      val pair                = AssetPair.createAssetPair("WAVES", assetId).get
      val buy                 = Order.buy(o1ver, buyer, matcher, pair, amount, price, ts, expirationTimestamp, matcherFee)
      val sell                = Order.sell(o2ver, seller, matcher, pair, amount, price, ts, expirationTimestamp, matcherFee)

      assertGrpcError(
        sender.exchange(matcher, buy, sell, amount, price, matcherFee, matcherFee, matcherFee, ts, tver),
        "Assets should be issued before they can be traded",
        Code.INVALID_ARGUMENT
      )
    }
  }

  test("failed exchange tx when asset script fails") {
    val seller         = firstAcc
    val buyer          = secondAcc
    val matcher        = thirdAcc
    val sellerAddress  = firstAddress
    val buyerAddress   = secondAddress
    val matcherAddress = thirdAddress

    val transfers = Seq(
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(sellerAddress), 100.waves, minFee),
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(buyerAddress), 100.waves, minFee),
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(matcherAddress), 100.waves, minFee)
    )

    val quantity                                        = 1000000000L
    val initScript: Either[Array[Byte], Option[Script]] = Right(ScriptCompiler.compile("true", ScriptEstimatorV3).toOption.map(_._1))
    val amountAsset                                     = sender.broadcastIssue(seller, "Amount asset", quantity, 8, reissuable = true, issueFee, script = initScript)
    val priceAsset                                      = sender.broadcastIssue(buyer, "Price asset", quantity, 8, reissuable = true, issueFee, script = initScript)
    val sellMatcherFeeAsset                             = sender.broadcastIssue(matcher, "Seller fee asset", quantity, 8, reissuable = true, issueFee, script = initScript)
    val buyMatcherFeeAsset                              = sender.broadcastIssue(matcher, "Buyer fee asset", quantity, 8, reissuable = true, issueFee, script = initScript)

    val preconditions = transfers ++ Seq(
      amountAsset,
      priceAsset,
      sellMatcherFeeAsset,
      buyMatcherFeeAsset
    )

    waitForTxs(preconditions)

    val sellMatcherFeeAssetId = PBTransactions.vanillaUnsafe(sellMatcherFeeAsset).id().toString
    val buyMatcherFeeAssetId  = PBTransactions.vanillaUnsafe(buyMatcherFeeAsset).id().toString

    val transferToSeller = sender.broadcastTransfer(
      matcher,
      Recipient().withPublicKeyHash(sellerAddress),
      quantity,
      fee = minFee + smartFee,
      assetId = sellMatcherFeeAssetId
    )
    val transferToBuyer = sender.broadcastTransfer(
      matcher,
      Recipient().withPublicKeyHash(buyerAddress),
      quantity,
      fee = minFee + smartFee,
      assetId = buyMatcherFeeAssetId
    )

    waitForTxs(Seq(transferToSeller, transferToBuyer))

    val amountAssetId  = PBTransactions.vanillaUnsafe(amountAsset).id().toString
    val priceAssetId   = PBTransactions.vanillaUnsafe(priceAsset).id().toString
    val assetPair      = AssetPair.createAssetPair(amountAssetId, priceAssetId).get
    val fee            = 0.003.waves + 4 * smartFee
    val sellMatcherFee = fee / 100000L
    val buyMatcherFee  = fee / 100000L
    val priorityFee    = setAssetScriptFee + smartFee + fee * 10

    val allCases =
      Seq((amountAssetId, seller), (priceAssetId, buyer), (sellMatcherFeeAssetId, matcher), (buyMatcherFeeAssetId, matcher))

    for ((invalidScriptAsset, owner) <- allCases) {
      val txsSend = (_: Int) => {
        val tx = PBTransactions.protobuf(
          mkExchange(buyer, seller, matcher, assetPair, fee, buyMatcherFeeAssetId, sellMatcherFeeAssetId, buyMatcherFee, sellMatcherFee)
        )
        sender.broadcast(tx.transaction.get, tx.proofs)
      }
      sendTxsAndThenPriorityTx(
        txsSend,
        () => updateAssetScript(result = false, invalidScriptAsset, owner, priorityFee)
      )(assertFailedTxs)
      updateAssetScript(result = true, invalidScriptAsset, owner, setAssetScriptFee + smartFee)
    }
  }

  test("invalid exchange tx when account script fails") {
    val seller         = firstAcc
    val buyer          = secondAcc
    val matcher        = thirdAcc
    val sellerAddress  = firstAddress
    val buyerAddress   = secondAddress
    val matcherAddress = thirdAddress

    val transfers = Seq(
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(sellerAddress), 100.waves, minFee),
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(buyerAddress), 100.waves, minFee),
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(matcherAddress), 100.waves, minFee)
    )

    val quantity            = 1000000000L
    val amountAsset         = sender.broadcastIssue(seller, "Amount asset", quantity, 8, reissuable = true, issueFee)
    val priceAsset          = sender.broadcastIssue(buyer, "Price asset", quantity, 8, reissuable = true, issueFee)
    val sellMatcherFeeAsset = sender.broadcastIssue(matcher, "Seller fee asset", quantity, 8, reissuable = true, issueFee)
    val buyMatcherFeeAsset  = sender.broadcastIssue(matcher, "Buyer fee asset", quantity, 8, reissuable = true, issueFee)

    val preconditions = transfers ++ Seq(
      amountAsset,
      priceAsset,
      sellMatcherFeeAsset,
      buyMatcherFeeAsset
    )

    waitForTxs(preconditions)

    val sellMatcherFeeAssetId = PBTransactions.vanillaUnsafe(sellMatcherFeeAsset).id().toString
    val buyMatcherFeeAssetId  = PBTransactions.vanillaUnsafe(buyMatcherFeeAsset).id().toString

    val transferToSeller = sender.broadcastTransfer(
      matcher,
      Recipient().withPublicKeyHash(sellerAddress),
      quantity,
      fee = minFee + smartFee,
      assetId = sellMatcherFeeAssetId
    )
    val transferToBuyer = sender.broadcastTransfer(
      matcher,
      Recipient().withPublicKeyHash(buyerAddress),
      quantity,
      fee = minFee + smartFee,
      assetId = buyMatcherFeeAssetId
    )

    waitForTxs(Seq(transferToSeller, transferToBuyer))

    val amountAssetId  = PBTransactions.vanillaUnsafe(amountAsset).id().toString
    val priceAssetId   = PBTransactions.vanillaUnsafe(priceAsset).id().toString
    val assetPair      = AssetPair.createAssetPair(amountAssetId, priceAssetId).get
    val fee            = 0.003.waves + smartFee
    val sellMatcherFee = fee / 100000L
    val buyMatcherFee  = fee / 100000L
    val priorityFee    = setScriptFee + smartFee + fee * 10

    val allCases = Seq(seller, buyer, matcher)
    allCases.foreach(address => updateAccountScript(None, address, setScriptFee + smartFee))

    for (invalidAccount <- allCases) {
      val txsSend = (_: Int) => {
        val tx = PBTransactions.protobuf(
          mkExchange(buyer, seller, matcher, assetPair, fee, buyMatcherFeeAssetId, sellMatcherFeeAssetId, buyMatcherFee, sellMatcherFee)
        )
        sender.broadcast(tx.transaction.get, tx.proofs)
      }

      sendTxsAndThenPriorityTx(
        txsSend,
        () => updateAccountScript(Some(false), invalidAccount, priorityFee)
      )(assertInvalidTxs)
      updateAccountScript(None, invalidAccount, setScriptFee + smartFee)
    }
  }

  private def waitForTxs(txs: Seq[PBSignedTransaction]): Unit = {
    txs.foreach(tx => sender.waitForTransaction(PBTransactions.vanillaUnsafe(tx).id().toString))
  }

  override protected def waitForHeightArise(): Unit = sender.waitForHeight(sender.height + 1)
}
