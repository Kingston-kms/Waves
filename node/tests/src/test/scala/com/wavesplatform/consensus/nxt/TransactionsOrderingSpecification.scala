package com.wavesplatform.consensus.nxt

import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.consensus.TransactionsOrdering
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.{Asset, SignedTx}
import org.scalatest.{Assertions, Matchers, PropSpec}

import scala.util.Random

class TransactionsOrderingSpecification extends PropSpec with Assertions with Matchers {

  private val kp: KeyPair = KeyPair(ByteStr(new Array[Byte](32)))
  property("TransactionsOrdering.InBlock should sort correctly") {
    val correctSeq = Seq(
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          125L,
          None,
          1
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          124L,
          None,
          2
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          124L,
          None,
          1
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Asset.fromCompatId(Some(ByteStr.empty)),
          124L,
          None,
          2
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Asset.fromCompatId(Some(ByteStr.empty)),
          124L,
          None,
          1
        )
    )

    val sorted = Random.shuffle(correctSeq).sorted(TransactionsOrdering.InBlock)

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool should sort correctly") {
    val correctSeq = Seq(
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          124L,
          None,
          1
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          123L,
          None,
          1
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          123L,
          None,
          2
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Asset.fromCompatId(Some(ByteStr.empty)),
          124L,
          None,
          1
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Asset.fromCompatId(Some(ByteStr.empty)),
          124L,
          None,
          2
        )
    )

    val sorted = Random.shuffle(correctSeq).sorted(TransactionsOrdering.InUTXPool)

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InBlock should sort txs by decreasing block timestamp") {
    val correctSeq = Seq(
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          1,
          None,
          124L
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          1,
          None,
          123L
        )
    )

    Random.shuffle(correctSeq).sorted(TransactionsOrdering.InBlock) shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool should sort txs by ascending block timestamp") {
    val correctSeq = Seq(
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          1,
          None,
          123L
        ),
      SignedTx
        .transfer(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Waves,
          100000,
          Waves,
          1,
          None,
          124L
        )
    )
    Random.shuffle(correctSeq).sorted(TransactionsOrdering.InUTXPool) shouldBe correctSeq
  }
}