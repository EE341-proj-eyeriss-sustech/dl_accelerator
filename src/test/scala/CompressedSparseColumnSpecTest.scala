package dla.tests

import chisel3._
import chisel3.tester._
import chisel3.util._
import dla.pe.{DataAddrStreanIO, SPadAddrModule, SPadDataModule, SPadSizeConfig}
import org.scalatest._
class CompressedSparseColumnSpecTest extends FlatSpec with ChiselScalatestTester with Matchers {
  // def some common parameters and functions
  val inAddr = Seq(2, 5, 15, 6, 7, 15, 9, 12) // 15 means it is a zero column, don't record the first number
  val inData = Seq(1, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33) // zero column between 15 & 18, 24 & 27
  val inCount = Seq(1, 2, 0, 1, 3, 2, 3, 1, 3, 0, 1, 2)
  val zeroColumn = Seq(false, false, false, false, false, false, true, false, true, false, false, false)
  val outColumn = Seq(0, 0, 1, 1, 1, 2, 3, 4, 5, 5, 6, 7, 7, 7) // 3, 6 are zero column
  val outAddrReadData = Seq(2, 2, 5, 5, 5, 15, 6, 7, 15, 9, 9, 12, 12)
  val outDataReadIndex = Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
  /*
  * The input matrix is
  * | data | row | col |
  * | ---- | --- | --- |
  * |   1  |  1  |  0  |
  * |   3  |  2  |  0  |
  * |   6  |  0  |  1  |
  * |   9  |  1  |  1  |
  * |  12  |  3  |  1  |
  * |  15  |  2  |  2  |
  * |  18  |  3  |  4  |
  * |  21  |  1  |  5  |
  * |  24  |  3  |  5  |
  * |  27  |  0  |  7  |
  * |  30  |  1  |  7  |
  * |  33  |  2  |  7  |
  */
  val inDataWithIndex: Seq[(Int, Int)] = inData zip inCount
  def toBinary(i: Int, digits: Int = 8): String =
    String.format("%" + digits + "s", i.toBinaryString).replace(' ', '0')
  val inDataCountBinary: Seq[String] = inDataWithIndex.map{case (x: Int, y: Int) => toBinary(x, 8) + toBinary(y, 4)}
  val inDataCountDec: Seq[Int] = inDataCountBinary.map(x => Integer.parseInt(x, 2))

  behavior of "read and write the compressed sparse column format data"
  class SimplyCombineAddrDataSPad extends Module with SPadSizeConfig{
    val io = IO(new Bundle{
      val iactIOs = new DataAddrStreanIO(iactDataWidth, iactAddrWidth, commonLenWidth, commonLenWidth)
      val iactAddrWriteIdx: UInt = Output(UInt(commonLenWidth.W)) // use for test
      val iactDataReq: Bool = Input(Bool()) // control to read data vector
      // we are supposed to see address SPad and data SPad together
      val iactMatrixColumn: UInt = Output(UInt(commonLenWidth.W))
      val iactMatrixRow: UInt = Output(UInt(cscCountWidth.W))
      val iactMatrixData: UInt = Output(UInt(cscDataWidth.W))
      val iactMatrixDataBin: UInt = Output(UInt(iactDataWidth.W))
      val iactAddrReadEn: Bool = Output(Bool())
      val iactAddrReadData: UInt = Output(UInt(iactAddrWidth.W))
      // val iactAddrReadIndex: UInt = Output(UInt(commonLenWidth.W)) = iactMatrixColumn
      val iactDataReadIndex: UInt = Output(UInt(commonLenWidth.W))
      val iactDataWriteIdx: UInt = Output(UInt(commonLenWidth.W))
    })
    val iactAddrSPad: SPadAddrModule = Module(new SPadAddrModule(commonLenWidth, iactAddrSPadSize, iactAddrWidth))
    val iactDataSPad: SPadDataModule = Module(new SPadDataModule(commonLenWidth, iactDataSPadSize, iactDataWidth, false))

    val padIdle :: padIactAddr :: padIactData :: Nil = Enum(3)
    val sPad: UInt = RegInit(padIdle)
    val iactASPRERegEnable: Bool = Wire(Bool())
    iactASPRERegEnable := sPad === padIactData
    val iactAddrIndexWire: UInt = Wire(UInt(commonLenWidth.W))
    val iactAddrDataWire: UInt = Wire(UInt(iactAddrWidth.W))
    val iactDataIndexWire: UInt = Wire(UInt(commonLenWidth.W)) // use for address vector readEn
    val iactSPadZeroColumnWire: Bool = Wire(Bool()) // true, it is a zero column, then need read again
    iactSPadZeroColumnWire := iactAddrDataWire === zeroColumnCode.U
    val iactAddrSPadReadEnReg: Bool = RegEnable(false.B, iactASPRERegEnable)
    val iactDataSPadReadEnReg: Bool = RegInit(false.B)
    val iactAddrSPadIdxIncWire: Bool = Wire(Bool()) // true, then increase the read index of address SPad
    iactAddrSPad.io.addrIO.indexInc := iactAddrSPadIdxIncWire
    iactAddrSPadIdxIncWire := ((sPad === padIactData) && (iactAddrDataWire === iactDataIndexWire)) || ((sPad === padIdle) && iactSPadZeroColumnWire)
    //iactAddrSPadIdxIncWire := ((sPad === padIdle) && io.iactDataReq && (iactAddrDataWire === iactDataIndexWire)) || ((sPad === padIactAddr) && iactSPadZeroColumnWire)
    iactAddrSPad.io.commonIO.dataLenFinIO <> io.iactIOs.addrIOs // this is different from the real module
    iactDataSPad.io.commonIO.dataLenFinIO <> io.iactIOs.dataIOs
    io.iactAddrWriteIdx := iactAddrSPad.io.commonIO.writeIdx
    io.iactDataWriteIdx := iactDataSPad.io.commonIO.writeIdx
    // AddrSPad
    iactAddrIndexWire := iactAddrSPad.io.commonIO.columnNum
    io.iactMatrixColumn := iactAddrIndexWire // for debug
    iactAddrDataWire := iactAddrSPad.io.commonIO.readOutData
    io.iactAddrReadData := iactAddrDataWire // for debug
    iactAddrSPad.io.commonIO.readEn := iactAddrSPadReadEnReg
    io.iactAddrReadEn := iactAddrSPadReadEnReg
    iactAddrSPad.io.dataIO.readInIdx := DontCare
    iactAddrSPad.io.addrIO.readInIdx := DontCare
    // DataSPad
    iactDataIndexWire := iactDataSPad.io.commonIO.columnNum
    io.iactDataReadIndex := iactDataIndexWire // for debug
    io.iactMatrixDataBin := iactDataSPad.io.commonIO.readOutData
    val iactDataCountVec: Seq[Bool] = iactDataSPad.io.commonIO.readOutData.asBools
    io.iactMatrixData := Cat(iactDataCountVec.reverse.take(8)).asUInt // TODO: figure out why it need reverse
    io.iactMatrixRow := Cat(iactDataCountVec.reverse.takeRight(4)).asUInt
    iactDataSPad.io.commonIO.readEn := io.iactDataReq
    iactDataSPad.io.commonIO.readEn := iactDataSPadReadEnReg
    iactDataSPad.io.dataIO.readInIdx := DontCare
    iactDataSPad.io.addrIO.readInIdx := DontCare
    iactDataSPad.io.addrIO.indexInc := DontCare
    // the_index_m0 = m0 + count_m0
    // addr_m0_index*M0
    // SPad read state machine
    switch (sPad) {
      is(padIdle) {
        when(io.iactDataReq) {
          when (iactSPadZeroColumnWire) {
            sPad := padIdle
            iactAddrSPadReadEnReg := true.B
            iactDataSPadReadEnReg := false.B
          } .otherwise{
            sPad := padIactAddr
            when (iactAddrIndexWire === 0.U) { // then it is the beginning of this read
              iactAddrSPadReadEnReg := true.B
            }
          }
        }
      }
      is(padIactAddr) {
        // state transform
        sPad := padIactData
        iactAddrSPadReadEnReg := false.B
        iactDataSPadReadEnReg := true.B
        /*
        when(iactSPadZeroColumnWire) { // this is a zero column
          sPad := padIactAddr
          iactAddrSPadReadEnReg := true.B
          iactDataSPadReadEnReg := false.B
        }.otherwise {
          sPad := padIactData
          iactAddrSPadReadEnReg := false.B
          iactDataSPadReadEnReg := true.B
        }*/
      }
      is(padIactData) {
        sPad := padIdle
        iactDataSPadReadEnReg := false.B
        iactAddrSPadReadEnReg := iactAddrDataWire === iactDataIndexWire
      }
    }
  }

  it should "try to read and write the data with csc format" in {
    test(new SimplyCombineAddrDataSPad) { iactSPad =>
      val theTopIO = iactSPad.io
      val theClock = iactSPad.clock
      println("------ begin to test the read ------")
      println("------ and write address in Iact SPad ------")
      println("----------- begin to write -----------")
      theTopIO.iactIOs.addrIOs.streamLen.poke(inAddr.length.U)
      theTopIO.iactIOs.dataIOs.streamLen.poke(inDataCountDec.length.U)
      theTopIO.iactDataReq.poke(false.B)
      fork { // run them in parallel
        theTopIO.iactIOs.addrIOs.streamDecoupledDataIO.valid.poke(true.B)
        for (i <- inAddr.indices) {
          theTopIO.iactIOs.addrIOs.streamDecoupledDataIO.bits.data.poke(inAddr(i).U)
          // theTopIO.iactIOs.addrIOs.streamDecoupledDataIO.bits.data.expect(inAddr(i).U)
          theTopIO.iactAddrWriteIdx.expect(i.U, s"i = $i")
          theTopIO.iactIOs.addrIOs.writeFin.expect((i == inAddr.length - 1).B, s"i = $i")
          theTopIO.iactIOs.addrIOs.streamDecoupledDataIO.ready.expect(true.B, "write valid, after receive the data, it should be ready")
          theClock.step(1)
        }
        theTopIO.iactIOs.addrIOs.streamDecoupledDataIO.valid.poke(false.B)
      } .fork {
        theTopIO.iactIOs.dataIOs.streamDecoupledDataIO.valid.poke(true.B)
        for (i <- inDataCountDec.indices) {
          theTopIO.iactIOs.dataIOs.streamDecoupledDataIO.bits.data.poke(inDataCountDec(i).U)
          theTopIO.iactDataWriteIdx.expect(i.U, s"i = $i")
          theTopIO.iactIOs.dataIOs.writeFin.expect((i == inDataCountDec.length - 1).B, s"i = $i")
          theTopIO.iactIOs.dataIOs.streamDecoupledDataIO.ready.expect(true.B, "write valid, after receive the data, it should be ready")
          theClock.step(1)
        }
        theTopIO.iactIOs.dataIOs.streamDecoupledDataIO.valid.poke(false.B)
      }.join()
      println("----------- begin to read -----------")
      theTopIO.iactDataReq.poke(true.B) // start the state machine
      var j:Int = 0 // for address cycle
      for (i <- 0 until 12) {
        println(s"------ read cycle $i ---------")
        if (!zeroColumn(i)) {
          println(s"addrReadEn = ${theTopIO.iactAddrReadEn.peek()}, addrReadData = ${theTopIO.iactAddrReadData.peek()}, dataReadIndex = ${theTopIO.iactDataReadIndex.peek()}")
          println(s"data = ${theTopIO.iactMatrixData.peek()}, row = ${theTopIO.iactMatrixRow.peek()}, column = ${theTopIO.iactMatrixColumn.peek()}")
          theClock.step(1) // from idle to address SPad read
          //theTopIO.iactAddrReadData.expect(outAddrReadData(j).U)
          //theTopIO.iactMatrixColumn.expect(outColumn(j).U, s"the column number should be ${outColumn(j)} at $i-th read cycle")
          theClock.step(1) // from address SPad read to data SPad read
          println(s"addrReadEn = ${theTopIO.iactAddrReadEn.peek()}, addrReadData = ${theTopIO.iactAddrReadData.peek()}, dataReadIndex = ${theTopIO.iactDataReadIndex.peek()}")
          println(s"data = ${theTopIO.iactMatrixData.peek()}, row = ${theTopIO.iactMatrixRow.peek()}, column = ${theTopIO.iactMatrixColumn.peek()}")
          theTopIO.iactMatrixRow.expect(inCount(i).U, s"the row number should be ${inCount(i)} at $i-th read cycle")
          theTopIO.iactMatrixData.expect(inData(i).U, s"the data value should be ${inData(i)} at $i-th read cycle")
          theTopIO.iactDataReadIndex.expect(outDataReadIndex(i).U, s"the data read index should be ${outDataReadIndex(i)} at $i-th read cycle")
          println(s"data = ${inData(i)}, row = ${inCount(i)}, column = ${outColumn(j)}")
          println(s"addrReadData = ${outAddrReadData(i)}, dataReadIndex = ${outDataReadIndex(i)}")
          j = j + 1
        }else{
          println(s"data = ${theTopIO.iactMatrixData.peek()}, row = ${theTopIO.iactMatrixRow.peek()}, column = ${theTopIO.iactMatrixColumn.peek()}")
          println(s"addrReadEn = ${theTopIO.iactAddrReadEn.peek()}, addrReadData = ${theTopIO.iactAddrReadData.peek()}, dataReadIndex = ${theTopIO.iactDataReadIndex.peek()}")
          //theTopIO.iactMatrixColumn.expect(outColumn(j).U, s"the column number should be ${outColumn(j)} at $i-th read cycle")
          //theTopIO.iactAddrReadData.expect(outAddrReadData(j).U)
          theClock.step(1) // from idle to next idle
          println(s"--- meets a zero column at $i read cycle ---")
          j = j + 1
          theClock.step(1) // from idle to next data SPad
          //theTopIO.iactMatrixColumn.expect(outColumn(j).U, s"the column number should be ${outColumn(j)} at $i-th read cycle")
          //theTopIO.iactAddrReadData.expect(outAddrReadData(j).U)
          println(s"data = ${theTopIO.iactMatrixData.peek()}, row = ${theTopIO.iactMatrixRow.peek()}, column = ${theTopIO.iactMatrixColumn.peek()}")
          println(s"addrReadEn = ${theTopIO.iactAddrReadEn.peek()}, addrReadData = ${theTopIO.iactAddrReadData.peek()}, dataReadIndex = ${theTopIO.iactDataReadIndex.peek()}")
          theClock.step(1) // from address SPad read to data SPad read
          println(s"data = ${theTopIO.iactMatrixData.peek()}, row = ${theTopIO.iactMatrixRow.peek()}, column = ${theTopIO.iactMatrixColumn.peek()}")
          println(s"addrReadEn = ${theTopIO.iactAddrReadEn.peek()}, addrReadData = ${theTopIO.iactAddrReadData.peek()}, dataReadIndex = ${theTopIO.iactDataReadIndex.peek()}")
          theTopIO.iactMatrixRow.expect(inCount(i).U, s"the row number should be ${inCount(i)}")
          theTopIO.iactMatrixData.expect(inData(i).U, s"the data value should be ${inData(i)}")
          println(s"data = ${inData(i)}, row = ${inCount(i)}, column = ${outColumn(j)}, ")
          j = j + 1
        }
        theClock.step(1) // from data SPad read to idle
      }
    }
  }

  it should "basically write and read address in Iact SPad" in {
    test(new SPadAddrModule(4, 9, 4)) { addrSPad =>
      val theCommonIO = addrSPad.io.commonIO
      val theDataIO = addrSPad.io.commonIO.dataLenFinIO.streamDecoupledDataIO
      val theClock = addrSPad.clock
      println("--- begin to test the read and write address in Iact SPad ---")
      println("----------- begin to write -----------")
      theCommonIO.dataLenFinIO.streamLen.poke(inAddr.length.U)
      theDataIO.valid.poke(true.B)
      theCommonIO.readEn.poke(false.B)
      for (i <- inAddr.indices) {
        theDataIO.bits.data.poke(inAddr(i).U)
        theCommonIO.writeIdx.expect(i.U, s"i = $i")
        theCommonIO.dataLenFinIO.writeFin.expect((i == inAddr.length - 1).B, s"i = $i")
        theDataIO.ready.expect(true.B, "write valid, after receive the data, it should be ready")
        theClock.step(1)
      }
      println("----------- begin to read -----------")
      theDataIO.valid.poke(false.B)
      theCommonIO.readEn.poke(true.B)
      addrSPad.io.addrIO.indexInc.poke(true.B) // INCREASE ALL THE TIME
      for (i <- 0 until (inAddr.length - 1)) {
        println(s"----------- read clock $i -----------")
        theCommonIO.columnNum.expect(i.U, s"columnNum = $i")
        theCommonIO.readOutData.expect(inAddr(i).U, s"readOutData = inData($i) = ${inAddr(i)}")
        println(s"theCommonIO.columnNum = $i")
        println(s"theCommonIO.readOutData = ${inAddr(i)}")
        theClock.step(1)
      }
      theCommonIO.readOutData.expect(inAddr.last.U, s"readOutData = inData(${inAddr.length - 1}) = ${inAddr.last}")
    }
  }
  it should "basically write and read data in Iact SPad" in {
    test(new SPadDataModule(4, 16, 12, false)) { dataSPad =>
      val theCommonIO = dataSPad.io.commonIO
      val theDataIO = dataSPad.io.commonIO.dataLenFinIO.streamDecoupledDataIO
      val theClock = dataSPad.clock
      println("--- begin to test the read and write data in Iact SPad ---")
      println("----------- begin to write -----------")
      theCommonIO.dataLenFinIO.streamLen.poke(inDataCountDec.length.U)
      theDataIO.valid.poke(true.B)
      theCommonIO.readEn.poke(false.B)
      for (i <- inDataCountDec.indices) {
        theDataIO.bits.data.poke(inDataCountDec(i).U)
        theDataIO.ready.expect(true.B, "write valid, after receive the data, it should be ready")
        theCommonIO.dataLenFinIO.writeFin.expect((i == inDataCountDec.length - 1).B, s"i = $i")
        theCommonIO.writeIdx.expect(i.U, s"i = $i")
        theClock.step(1)
      }
      println("----------- begin to read -----------")
      theDataIO.valid.poke(false.B)
      theCommonIO.readEn.poke(true.B)
      for (i <- inDataCountDec.indices) {
        println(s"----------- read clock $i -----------")
        theCommonIO.columnNum.expect(i.U, s"columnNum = $i in read clock $i")
        theCommonIO.readOutData.expect(inDataCountDec(i).U, s"readOutData = inDataCountDec($i) = ${inDataCountDec(i)}")
        println(s"theCommonIO.columnNum = $i")
        println(s"theCommonIO.readOutData = ${inDataCountDec(i)}")
        theClock.step(1)
      }
      theCommonIO.columnNum.expect(0.U, s"new read turn begins, columnNum = 0")
      theCommonIO.readOutData.expect(inDataCountDec.head.U, s"new read turn begins, readOutData = ${inDataCountDec.head}")
    }
  }
}