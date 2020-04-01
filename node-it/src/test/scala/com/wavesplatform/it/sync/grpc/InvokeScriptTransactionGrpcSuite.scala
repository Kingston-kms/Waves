package com.wavesplatform.it.sync.grpc

import com.google.common.primitives.Longs
import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base58, EitherExt2}
import com.wavesplatform.it.api.SyncGrpcApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.sync.smartcontract.invokeScrTxSupportedVersions
import com.wavesplatform.it.sync.transactions.PriorityTransaction
import com.wavesplatform.it.util._
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.compiler.Terms.{CONST_BYTESTR, FUNCTION_CALL}
import com.wavesplatform.lang.v1.estimator.v2.ScriptEstimatorV2
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.protobuf.Amount
import com.wavesplatform.protobuf.transaction.DataTransactionData.DataEntry
import com.wavesplatform.protobuf.transaction.{PBRecipients, PBSignedTransaction, PBTransactions, Recipient}
import com.wavesplatform.state.{BinaryDataEntry, BooleanDataEntry, IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.TxVersion
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import io.grpc.Status.Code

class InvokeScriptTransactionGrpcSuite extends GrpcBaseTransactionSuite with PriorityTransaction {
  import grpcApi._

  private val (firstContract, firstContractAddr)   = (firstAcc, firstAddress)
  private val (secondContract, secondContractAddr) = (secondAcc, secondAddress)
  private val thirdContract                        = KeyPair("thirdContract".getBytes("UTF-8"))
  private val thirdContractAddr                    = PBRecipients.create(Address.fromPublicKey(thirdContract.publicKey)).getPublicKeyHash
  private val caller                               = thirdAcc
  private val callerAddr                           = PBRecipients.create(Address.fromPublicKey(thirdAcc.publicKey)).getPublicKeyHash

  private val maxTxsInMicroBlock = sender.config.getInt("waves.miner.max-transactions-in-micro-block")

  private val assetAmount    = 1000000000L
  private var smartAsset     = ""
  private var sponsoredAsset = ""

  protected override def beforeAll(): Unit = {
    super.beforeAll()

    sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(thirdContractAddr), 100.waves, minFee, waitForTx = true)

    smartAsset = PBTransactions
      .vanillaUnsafe(
        sender
          .broadcastIssue(
            thirdContract,
            "Asset",
            assetAmount,
            8,
            reissuable = true,
            issueFee,
            description = "Description",
            script = Right(ScriptCompiler.compile("true", ScriptEstimatorV3).toOption.map(_._1)),
            waitForTx = true
          )
      )
      .id()
      .toString

    sponsoredAsset = PBTransactions
      .vanillaUnsafe(
        sender
          .broadcastIssue(
            thirdContract,
            "Sponsored Asset",
            assetAmount,
            8,
            reissuable = true,
            issueFee,
            "Description",
            script = Right(None),
            waitForTx = true
          )
      )
      .id()
      .toString

    val scriptText =
      """
        |{-# STDLIB_VERSION 3 #-}
        |{-# CONTENT_TYPE DAPP #-}
        |
        | @Callable(inv)
        | func foo(a:ByteVector) = {
        |  WriteSet([DataEntry("a", a), DataEntry("sender", inv.caller.bytes)])
        | }
        | @Callable(inv)
        | func emptyKey() = {
        |  WriteSet([DataEntry("", "a")])
        | }
        |
        | @Callable(inv)
        | func default() = {
        |  WriteSet([DataEntry("a", "b"), DataEntry("sender", "senderId")])
        | }
        |
        | @Verifier(tx)
        | func verify() = {
        |    match tx {
        |        case TransferTransaction => false
        |        case _ => true
        | }
        |}
        |
        """.stripMargin
    val scriptTextV4 =
      s"""
        |{-# STDLIB_VERSION 4 #-}
        |{-# CONTENT_TYPE DAPP #-}
        |
        |let asset = base58'$smartAsset'
        |
        | @Callable(inv)
        |func foo() = [IntegerEntry("", 1)]
        |
        | @Callable(inv)
        |func bar() = [IntegerEntry("", 2)]
        |
        |@Callable(inv)
        |func tikTok() = {
        |  let action = valueOrElse(getString(this, "tikTok"), "unknown")
        |  if (action == "transfer") then [ScriptTransfer(inv.caller, 15, asset)]
        |  else if (action == "issue") then [Issue("new asset", "", 100, 8, true, unit, 0)]
        |  else if (action == "reissue") then [Reissue(asset, true, 15)]
        |  else if (action == "burn") then [Burn(asset, 15)]
        |  else []
        |}
        |
        |@Callable(inv)
        |func transferAndWrite(x: Int) = {
        |  if (x % 4 == 0) then [ScriptTransfer(inv.caller, 15, asset), IntegerEntry("n", x)]
        |  else if (x % 4 == 1) then [ScriptTransfer(inv.caller, 15, asset), BooleanEntry("b", x % 2 == 0)]
        |  else if (x % 4 == 2) then [ScriptTransfer(inv.caller, 15, asset), BinaryEntry("bn", toBytes(x))]
        |  else if (x % 4 == 3) then [ScriptTransfer(inv.caller, 15, asset), StringEntry("s", toString(x))]
        |  else []
        |}
        |
        """.stripMargin
    val script  = ScriptCompiler.compile(scriptText, ScriptEstimatorV2).explicitGet()._1
    val script2 = ScriptCompiler.compile(scriptTextV4, ScriptEstimatorV3).explicitGet()._1
    sender.setScript(firstContract, Right(Some(script)), setScriptFee, waitForTx = true)
    sender.setScript(secondContract, Right(Some(script)), setScriptFee, waitForTx = true)
    sender.setScript(thirdContract, Right(Some(script2)), setScriptFee, waitForTx = true)

    val scriptInfo = sender.scriptInfo(firstAddress)
    PBTransactions.toVanillaScript(scriptInfo.scriptBytes) shouldBe Some(script)
  }

  test("dApp caller invokes a function on a dApp") {
    val arg = ByteStr(Array(42: Byte))
    for (v <- invokeScrTxSupportedVersions) {
      val contract = if (v < 2) firstContractAddr else secondContractAddr
      sender.broadcastInvokeScript(
        caller,
        Recipient().withPublicKeyHash(contract),
        Some(FUNCTION_CALL(FunctionHeader.User("foo"), List(CONST_BYTESTR(arg).explicitGet()))),
        fee = 1.waves,
        waitForTx = true
      )

      sender.getDataByKey(contract, "a") shouldBe List(DataEntry("a", DataEntry.Value.BinaryValue(ByteString.copyFrom(arg))))
      sender.getDataByKey(contract, "sender") shouldBe List(
        DataEntry("sender", DataEntry.Value.BinaryValue(ByteString.copyFrom(caller.toAddress.bytes)))
      )
    }
  }

  test("dApp caller invokes a default function on a dApp") {
    for (v <- invokeScrTxSupportedVersions) {
      val contract = if (v < 2) firstContractAddr else secondContractAddr
      sender.broadcastInvokeScript(
        caller,
        Recipient().withPublicKeyHash(contract),
        functionCall = None,
        fee = 1.waves,
        waitForTx = true
      )
      sender.getDataByKey(contract, "a") shouldBe List(DataEntry("a", DataEntry.Value.StringValue("b")))
      sender.getDataByKey(contract, "sender") shouldBe List(DataEntry("sender", DataEntry.Value.StringValue("senderId")))
    }
  }

  test("verifier works") {
    for (v <- invokeScrTxSupportedVersions) {
      val contract    = if (v < 2) firstContractAddr else secondContractAddr
      val dAppBalance = sender.wavesBalance(contract)
      assertGrpcError(
        sender.broadcastTransfer(firstAcc, Recipient().withPublicKeyHash(contract), transferAmount, 1.waves),
        "Transaction is not allowed by account-script",
        Code.INVALID_ARGUMENT
      )
      sender.wavesBalance(contract) shouldBe dAppBalance
    }
  }

  test("not able to set an empty key by InvokeScriptTransaction with version >= 2") {
    assertGrpcError(
      sender.broadcastInvokeScript(
        caller,
        Recipient().withPublicKeyHash(secondContractAddr),
        Some(FUNCTION_CALL(FunctionHeader.User("emptyKey"), List.empty)),
        fee = 1.waves,
        version = TxVersion.V2
      ),
      "State check failed. Reason: Empty keys aren't allowed in tx version >= 2",
      Code.INVALID_ARGUMENT
    )

    assertGrpcError(
      sender.broadcastInvokeScript(
        caller,
        Recipient().withPublicKeyHash(thirdContractAddr),
        Some(FUNCTION_CALL(FunctionHeader.User("foo"), List.empty)),
        fee = 1.waves,
        version = TxVersion.V2
      ),
      "State check failed. Reason: Empty keys aren't allowed in tx version >= 2",
      Code.INVALID_ARGUMENT
    )
  }

  test("insufficient action fees propagates failed transaction") {
    val invokeFee            = 0.005.waves
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee

    updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)

    for (typeName <- Seq("transfer", "issue", "reissue", "burn")) {
      updateTikTok("unknown", setAssetScriptMinFee)

      sendTxsAndThenPriorityTx(
        _ =>
          sender
            .broadcastInvokeScript(
              caller,
              Recipient().withPublicKeyHash(thirdContractAddr),
              Some(FUNCTION_CALL(FunctionHeader.User("tikTok"), List.empty)),
              fee = invokeFee
            ),
        () => updateTikTok(typeName, priorityFee)
      )(assertFailedTxs)
    }
  }

  test("invoke script error propagates failed transaction") {
    val invokeFee            = 0.005.waves + smartFee
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee

    for (funcName <- Seq("transfer", "reissue", "burn")) {
      updateTikTok(funcName, setAssetScriptMinFee)
      updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)

      sendTxsAndThenPriorityTx(
        _ =>
          sender
            .broadcastInvokeScript(
              caller,
              Recipient().withPublicKeyHash(thirdContractAddr),
              Some(FUNCTION_CALL(FunctionHeader.User("tikTok"), List.empty)),
              fee = invokeFee
            ),
        () => updateAssetScript(result = false, smartAsset, thirdContract, priorityFee)
      )(assertFailedTxs)
    }
  }

  test("sponsored fee on failed transaction should be charged correctly") {
    val invokeFee            = 0.005.waves + smartFee
    val invokeFeeInAsset     = invokeFee / 100000 // assetFee = feeInWaves / feeUnit * sponsorship
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee
    val totalWavesSpend      = invokeFee * maxTxsInMicroBlock * 2

    updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)
    updateTikTok("reissue", setAssetScriptMinFee)

    sender.broadcastSponsorFee(
      thirdContract,
      Some(Amount.of(ByteString.copyFrom(Base58.decode(sponsoredAsset)), 1)),
      sponsorFee + smartFee,
      waitForTx = true
    )
    sender.broadcastTransfer(
      thirdContract,
      Recipient().withPublicKeyHash(callerAddr),
      assetAmount,
      smartMinFee,
      assetId = sponsoredAsset,
      waitForTx = true
    )
    val prevBalance = sender.wavesBalance(thirdContractAddr).regular

    sendTxsAndThenPriorityTx(
      _ =>
        sender.broadcastInvokeScript(
          caller,
          Recipient().withPublicKeyHash(thirdContractAddr),
          Some(FUNCTION_CALL(FunctionHeader.User("tikTok"), List.empty)),
          fee = invokeFeeInAsset,
          feeAssetId = ByteString.copyFrom(Base58.decode(sponsoredAsset))
        ),
      () => updateAssetScript(result = false, smartAsset, thirdContract, priorityFee)
    ) { txs =>
      sender.wavesBalance(thirdContractAddr).regular shouldBe prevBalance - totalWavesSpend - priorityFee
      assertFailedTxs(txs)
    }
  }

  test("account state should not be changed after accepting failed transaction") {
    val invokeFee            = 0.005.waves + smartFee
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee

    val initialEntries = List(
      IntegerDataEntry("n", -1),
      BooleanDataEntry("b", value = false),
      BinaryDataEntry("bn", ByteStr(Longs.toByteArray(-1))),
      StringDataEntry("s", "-1")
    ).map(PBTransactions.toPBDataEntry)
    sender.putData(thirdContract, initialEntries, minFee + smartFee)
    updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)

    sendTxsAndThenPriorityTx(
      i =>
        sender.broadcastInvokeScript(
          caller,
          Recipient().withPublicKeyHash(thirdContractAddr),
          Some(FUNCTION_CALL(FunctionHeader.User("transferAndWrite"), List(Terms.CONST_LONG(i)))),
          fee = invokeFee
        ),
      () => updateAssetScript(result = false, smartAsset, thirdContract, priorityFee)
    ) { txs =>
      val failed              = assertFailedTxs(txs)
      val lastSuccessEndArg   = txs.size - failed.size
      val lastSuccessStartArg = (lastSuccessEndArg - 3).max(1)

      val lastSuccessWrites =
        Range
          .inclusive(lastSuccessStartArg, lastSuccessEndArg)
          .map {
            case i if i % 4 == 0 => "n"  -> IntegerDataEntry("n", i)
            case i if i % 4 == 1 => "b"  -> BooleanDataEntry("b", i % 2 == 0)
            case i if i % 4 == 2 => "bn" -> BinaryDataEntry("bn", ByteStr(Longs.toByteArray(i)))
            case i if i % 4 == 3 => "s"  -> StringDataEntry("s", i.toString)
          }
          .toMap
          .mapValues(PBTransactions.toPBDataEntry)
      initialEntries.map(entry => entry.key -> entry).toMap.foreach {
        case (key, initial) =>
          sender.getDataByKey(thirdContractAddr, key) shouldBe List(lastSuccessWrites.getOrElse(key, initial))
      }
      failed
    }
  }

  test("reject transactions if account script failed") {
    val invokeFee            = 0.005.waves
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee

    updateTikTok("unknown", setAssetScriptMinFee)
    updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)

    val prevBalance = sender.wavesBalance(callerAddr).regular

    sendTxsAndThenPriorityTx(
      _ =>
        sender.broadcastInvokeScript(
          caller,
          Recipient().withPublicKeyHash(thirdContractAddr),
          Some(FUNCTION_CALL(FunctionHeader.User("tikTok"), List.empty)),
          fee = invokeFee
        ),
      () =>
        sender.setScript(
          caller,
          Right(
            ScriptCompiler
              .compile(
                s"""
                 |{-# STDLIB_VERSION 3 #-}
                 |{-# CONTENT_TYPE EXPRESSION #-}
                 |{-# SCRIPT_TYPE ACCOUNT #-}
                 |
                 |match (tx) {
                 |case t: InvokeScriptTransaction => false
                 |case _ => true
                 |}
                 |""".stripMargin,
                ScriptEstimatorV3
              )
              .toOption
              .map(_._1)
          ),
          fee = priorityFee,
          waitForTx = true
        )
    ) { txs =>
      val invalid = assertInvalidTxs(txs)
      sender.wavesBalance(callerAddr).regular shouldBe prevBalance - (txs.size - invalid.size) * invokeFee - priorityFee
      invalid
    }
  }

  private def updateTikTok(result: String, fee: Long): PBSignedTransaction =
    sender.putData(thirdContract, List(StringDataEntry("tikTok", result)).map(PBTransactions.toPBDataEntry), fee = fee, waitForTx = true)

  override protected def waitForHeightArise(): Unit = sender.waitForHeight(sender.height + 1)
}
