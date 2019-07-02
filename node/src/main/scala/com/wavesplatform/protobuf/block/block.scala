package com.wavesplatform.protobuf

//noinspection TypeAnnotation
package object block {
  type PBBlock = com.wavesplatform.protobuf.block.Block
  val PBBlock = com.wavesplatform.protobuf.block.Block

  type VanillaBlock = com.wavesplatform.block.Block
  val VanillaBlock = com.wavesplatform.block.Block

  type PBSignedMicroBlock = com.wavesplatform.protobuf.block.SignedMicroBlock
  val PBSignedMicroBlock = com.wavesplatform.protobuf.block.SignedMicroBlock

  type PBMicroBlock = com.wavesplatform.protobuf.block.MicroBlock
  val PBMicroBlock = com.wavesplatform.protobuf.block.MicroBlock

  type VanillaMicroBlock = com.wavesplatform.block.MicroBlock
  val VanillaMicroBlock = com.wavesplatform.block.MicroBlock

  type VanillaBlockHeader = com.wavesplatform.block.BlockHeader
  val VanillaBlockHeader = com.wavesplatform.block.BlockHeader
}
