package com.wavesplatform.transaction

//noinspection TypeAnnotation
package object protobuf {
  type PBOrder = com.wavesplatform.transaction.protobuf.ExchangeTransactionData.Order
  val PBOrder = com.wavesplatform.transaction.protobuf.ExchangeTransactionData.Order

  type VanillaOrder = com.wavesplatform.transaction.assets.exchange.Order
  val VanillaOrder = com.wavesplatform.transaction.assets.exchange.Order

  type PBTransaction = com.wavesplatform.transaction.protobuf.Transaction
  val PBTransaction = com.wavesplatform.transaction.protobuf.Transaction

  type PBSignedTransaction = com.wavesplatform.transaction.protobuf.SignedTransaction
  val PBSignedTransaction = com.wavesplatform.transaction.protobuf.SignedTransaction

  type VanillaTransaction = com.wavesplatform.transaction.Transaction
  val VanillaTransaction = com.wavesplatform.transaction.Transaction

  type VanillaSignedTransaction = com.wavesplatform.transaction.SignedTransaction

  type VanillaAssetId = com.wavesplatform.transaction.AssetId
}