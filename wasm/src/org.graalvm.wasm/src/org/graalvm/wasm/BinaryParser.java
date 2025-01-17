/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.wasm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import org.graalvm.wasm.collection.ByteArrayList;
import org.graalvm.wasm.constants.CallIndirect;
import org.graalvm.wasm.constants.ExportIdentifier;
import org.graalvm.wasm.constants.GlobalModifier;
import org.graalvm.wasm.constants.GlobalResolution;
import org.graalvm.wasm.constants.ImportIdentifier;
import org.graalvm.wasm.constants.LimitsPrefix;
import org.graalvm.wasm.exception.WasmException;
import org.graalvm.wasm.exception.WasmLinkerException;
import org.graalvm.wasm.memory.WasmMemory;
import org.graalvm.wasm.nodes.WasmBlockNode;
import org.graalvm.wasm.nodes.WasmCallStubNode;
import org.graalvm.wasm.nodes.WasmEmptyNode;
import org.graalvm.wasm.nodes.WasmIfNode;
import org.graalvm.wasm.nodes.WasmIndirectCallNode;
import org.graalvm.wasm.nodes.WasmNode;
import org.graalvm.wasm.nodes.WasmRootNode;
import org.graalvm.wasm.constants.Instructions;
import org.graalvm.wasm.constants.Section;

/**
 * Simple recursive-descend parser for the binary WebAssembly format.
 */
public class BinaryParser extends BinaryStreamParser {

    private static final int MAGIC = 0x6d736100;
    private static final int VERSION = 0x00000001;

    private WasmLanguage language;
    private WasmModule module;
    private byte[] bytesConsumed;

    /**
     * Modules may import, as well as define their own functions. Function IDs are shared among
     * imported and defined functions. This variable keeps track of the function indices, so that
     * imported and parsed code entries can be correctly associated to their respective functions
     * and types.
     */
    // TODO: We should remove this to reduce complexity - codeEntry state should be sufficient
    // to track the current largest function index.
    private int moduleFunctionIndex;

    BinaryParser(WasmLanguage language, WasmModule module, byte[] data) {
        super(data);
        this.language = language;
        this.module = module;
        this.bytesConsumed = new byte[1];
        this.moduleFunctionIndex = 0;
    }

    WasmModule readModule() {
        validateMagicNumberAndVersion();
        readSections();
        return module;
    }

    private void validateMagicNumberAndVersion() {
        Assert.assertIntEqual(read4(), MAGIC, "Invalid MAGIC number");
        Assert.assertIntEqual(read4(), VERSION, "Invalid VERSION number");
    }

    private void readSections() {
        while (!isEOF()) {
            byte sectionID = read1();
            int size = readUnsignedInt32();
            int startOffset = offset;
            switch (sectionID) {
                case Section.CUSTOM:
                    readCustomSection(size);
                    break;
                case Section.TYPE:
                    readTypeSection();
                    break;
                case Section.IMPORT:
                    readImportSection();
                    break;
                case Section.FUNCTION:
                    readFunctionSection();
                    break;
                case Section.TABLE:
                    readTableSection();
                    break;
                case Section.MEMORY:
                    readMemorySection();
                    break;
                case Section.GLOBAL:
                    readGlobalSection();
                    break;
                case Section.EXPORT:
                    readExportSection();
                    break;
                case Section.START:
                    readStartSection();
                    break;
                case Section.ELEMENT:
                    readElementSection();
                    break;
                case Section.CODE:
                    readCodeSection();
                    break;
                case Section.DATA:
                    readDataSection();
                    break;
                default:
                    Assert.fail("invalid section ID: " + sectionID);
            }
            Assert.assertIntEqual(offset - startOffset, size, String.format("Declared section (0x%02X) size is incorrect", sectionID));
        }
    }

    private void readCustomSection(int size) {
        // TODO: We skip the custom section for now, but we should see what we could typically pick
        // up here.
        offset += size;
    }

    private void readTypeSection() {
        int numTypes = readVectorLength();
        for (int t = 0; t != numTypes; ++t) {
            byte type = read1();
            switch (type) {
                case 0x60:
                    readFunctionType();
                    break;
                default:
                    Assert.fail("Only function types are supported in the type section");
            }
        }
    }

    private void readImportSection() {
        Assert.assertIntEqual(module.symbolTable().maxGlobalIndex(), -1,
                        "The global index should be -1 when the import section is first read.");
        final WasmContext context = WasmLanguage.getCurrentContext();
        int numImports = readVectorLength();
        for (int i = 0; i != numImports; ++i) {
            String moduleName = readName();
            String memberName = readName();
            byte importType = readImportType();
            switch (importType) {
                case ImportIdentifier.FUNCTION: {
                    int typeIndex = readTypeIndex();
                    module.symbolTable().importFunction(moduleName, memberName, typeIndex);
                    moduleFunctionIndex++;
                    break;
                }
                case ImportIdentifier.TABLE: {
                    byte elemType = readElemType();
                    Assert.assertIntEqual(elemType, ReferenceTypes.FUNCREF, "Invalid element type for table import");
                    byte limitsPrefix = read1();
                    switch (limitsPrefix) {
                        case LimitsPrefix.NO_MAX: {
                            int initSize = readUnsignedInt32();  // initial size (in number of
                                                                 // entries)
                            module.symbolTable().importTable(context, moduleName, memberName, initSize, -1);
                            break;
                        }
                        case LimitsPrefix.WITH_MAX: {
                            int initSize = readUnsignedInt32();  // initial size (in number of
                                                                 // entries)
                            int maxSize = readUnsignedInt32();  // max size (in number of entries)
                            module.symbolTable().importTable(context, moduleName, memberName, initSize, maxSize);
                            break;
                        }
                        default:
                            Assert.fail(String.format("Invalid limits prefix for imported table (expected 0x00 or 0x01, got 0x%02X", limitsPrefix));
                    }
                    break;
                }
                case ImportIdentifier.MEMORY: {
                    byte limitsPrefix = read1();
                    switch (limitsPrefix) {
                        case LimitsPrefix.NO_MAX: {
                            // Read initial size (in number of entries).
                            int initSize = readUnsignedInt32();
                            int maxSize = -1;
                            module.symbolTable().importMemory(context, moduleName, memberName, initSize, maxSize);
                            break;
                        }
                        case LimitsPrefix.WITH_MAX: {
                            // Read initial size (in number of entries).
                            int initSize = readUnsignedInt32();
                            // Read max size (in number of entries).
                            int maxSize = readUnsignedInt32();
                            module.symbolTable().importMemory(context, moduleName, memberName, initSize, maxSize);
                            break;
                        }
                        default:
                            Assert.fail(String.format("Invalid limits prefix for imported memory (expected 0x00 or 0x01, got 0x%02X", limitsPrefix));
                    }
                    break;
                }
                case ImportIdentifier.GLOBAL: {
                    byte type = readValueType();
                    // See GlobalModifier.
                    byte mutability = read1();
                    int index = module.symbolTable().maxGlobalIndex() + 1;
                    context.linker().importGlobal(module, index, moduleName, memberName, type, mutability);
                    break;
                }
                default: {
                    Assert.fail(String.format("Invalid import type identifier: 0x%02X", importType));
                }
            }
        }
    }

    private void readFunctionSection() {
        int numFunctions = readVectorLength();
        for (int i = 0; i != numFunctions; ++i) {
            int functionTypeIndex = readUnsignedInt32();
            module.symbolTable().declareFunction(functionTypeIndex);
        }
    }

    private void readTableSection() {
        int numTables = readVectorLength();
        Assert.assertIntLessOrEqual(module.symbolTable().tableCount() + numTables, 1, "Can import or declare at most one table per module.");
        // Since in the current version of WebAssembly supports at most one table instance per
        // module.
        // this loop should be executed at most once.
        for (byte tableIndex = 0; tableIndex != numTables; ++tableIndex) {
            byte elemType = readElemType();
            Assert.assertIntEqual(elemType, ReferenceTypes.FUNCREF, "Invalid element type for table");
            byte limitsPrefix = readLimitsPrefix();
            switch (limitsPrefix) {
                case LimitsPrefix.NO_MAX: {
                    int initSize = readUnsignedInt32();  // initial size (in number of entries)
                    module.symbolTable().allocateTable(WasmLanguage.getCurrentContext(), initSize, -1);
                    break;
                }
                case LimitsPrefix.WITH_MAX: {
                    int initSize = readUnsignedInt32();  // initial size (in number of entries)
                    int maxSize = readUnsignedInt32();  // max size (in number of entries)
                    Assert.assertIntLessOrEqual(initSize, maxSize, "Initial table size must be smaller or equal than maximum size");
                    module.symbolTable().allocateTable(WasmLanguage.getCurrentContext(), initSize, maxSize);
                    break;
                }
                default:
                    Assert.fail(String.format("Invalid limits prefix for table (expected 0x00 or 0x01, got 0x%02X", limitsPrefix));
            }
        }
    }

    private void readMemorySection() {
        int numMemories = readVectorLength();
        Assert.assertIntLessOrEqual(module.symbolTable().memoryCount() + numMemories, 1, "Can import or declare at most one memory per module.");
        // Since in the current version of WebAssembly supports at most one table instance per
        // module.
        // this loop should be executed at most once.
        for (int i = 0; i != numMemories; ++i) {
            byte limitsPrefix = readLimitsPrefix();
            switch (limitsPrefix) {
                case LimitsPrefix.NO_MAX: {
                    // Read initial size (in Wasm pages).
                    int initSize = readUnsignedInt32();
                    int maxSize = -1;
                    module.symbolTable().allocateMemory(WasmLanguage.getCurrentContext(), initSize, maxSize);
                    break;
                }
                case LimitsPrefix.WITH_MAX: {
                    // Read initial size (in Wasm pages).
                    int initSize = readUnsignedInt32();
                    // Read max size (in Wasm pages).
                    int maxSize = readUnsignedInt32();
                    module.symbolTable().allocateMemory(WasmLanguage.getCurrentContext(), initSize, maxSize);
                    break;
                }
                default:
                    Assert.fail(String.format("Invalid limits prefix for memory (expected 0x00 or 0x01, got 0x%02X", limitsPrefix));
            }
        }
    }

    private void readCodeSection() {
        int numCodeEntries = readVectorLength();
        WasmRootNode[] rootNodes = new WasmRootNode[numCodeEntries];
        for (int entry = 0; entry != numCodeEntries; ++entry) {
            rootNodes[entry] = createCodeEntry(moduleFunctionIndex + entry);
        }
        for (int entry = 0; entry != numCodeEntries; ++entry) {
            int codeEntrySize = readUnsignedInt32();
            int startOffset = offset;
            readCodeEntry(moduleFunctionIndex + entry, rootNodes[entry]);
            Assert.assertIntEqual(offset - startOffset, codeEntrySize, String.format("Code entry %d size is incorrect", entry));
        }
        moduleFunctionIndex += numCodeEntries;
    }

    private WasmRootNode createCodeEntry(int funcIndex) {
        final WasmFunction function = module.symbolTable().function(funcIndex);
        WasmCodeEntry codeEntry = new WasmCodeEntry(function, data);
        function.setCodeEntry(codeEntry);

        /*
         * Create the root node and create and set the call target for the body. This needs to be
         * done before reading the body block, because we need to be able to create direct call
         * nodes {@see TruffleRuntime#createDirectCallNode} during parsing.
         */
        WasmRootNode rootNode = new WasmRootNode(language, codeEntry);
        RootCallTarget callTarget = Truffle.getRuntime().createCallTarget(rootNode);
        function.setCallTarget(callTarget);

        return rootNode;
    }

    private void readCodeEntry(int funcIndex, WasmRootNode rootNode) {
        /*
         * Initialise the code entry local variables (which contain the parameters and the locals).
         */
        initCodeEntryLocals(funcIndex);

        /* Read (parse) and abstractly interpret the code entry */
        byte returnTypeId = module.symbolTable().function(funcIndex).returnType();
        ExecutionState state = new ExecutionState();
        WasmBlockNode bodyBlock = readBlockBody(rootNode.codeEntry(), state, returnTypeId, returnTypeId);
        rootNode.setBody(bodyBlock);

        /* Push a frame slot to the frame descriptor for every local. */
        rootNode.codeEntry().initLocalSlots(rootNode.getFrameDescriptor());

        /* Initialize the Truffle-related components required for execution. */
        rootNode.codeEntry().setByteConstants(state.byteConstants());
        rootNode.codeEntry().setIntConstants(state.intConstants());
        rootNode.codeEntry().setLongConstants(state.longConstants());
        rootNode.codeEntry().setBranchTables(state.branchTables());
        rootNode.codeEntry().initStackSlots(rootNode.getFrameDescriptor(), state.maxStackSize());
    }

    private ByteArrayList readCodeEntryLocals() {
        int numLocalsGroups = readVectorLength();
        ByteArrayList localTypes = new ByteArrayList();
        for (int localGroup = 0; localGroup < numLocalsGroups; localGroup++) {
            int groupLength = readVectorLength();
            byte t = readValueType();
            for (int i = 0; i != groupLength; ++i) {
                localTypes.add(t);
            }
        }
        return localTypes;
    }

    private void initCodeEntryLocals(int funcIndex) {
        WasmCodeEntry codeEntry = module.symbolTable().function(funcIndex).codeEntry();
        int typeIndex = module.symbolTable().function(funcIndex).typeIndex();
        ByteArrayList argumentTypes = module.symbolTable().functionTypeArgumentTypes(typeIndex);
        ByteArrayList localTypes = readCodeEntryLocals();
        byte[] allLocalTypes = ByteArrayList.concat(argumentTypes, localTypes);
        codeEntry.setLocalTypes(allLocalTypes);
    }

    @SuppressWarnings("unused")
    private static void checkValidStateOnBlockExit(byte returnTypeId, ExecutionState state, int initialStackSize) {
        if (returnTypeId == 0x40) {
            Assert.assertIntEqual(state.stackSize(), initialStackSize, "Void function left values in the stack");
        } else {
            Assert.assertIntEqual(state.stackSize(), initialStackSize + 1, "Function left more than 1 values left in stack");
        }
    }

    private WasmBlockNode readBlock(WasmCodeEntry codeEntry, ExecutionState state) {
        byte blockTypeId = readBlockType();
        return readBlockBody(codeEntry, state, blockTypeId, blockTypeId);
    }

    private LoopNode readLoop(WasmCodeEntry codeEntry, ExecutionState state) {
        byte blockTypeId = readBlockType();
        return readLoop(codeEntry, state, blockTypeId);
    }

    private WasmBlockNode readBlockBody(WasmCodeEntry codeEntry, ExecutionState state, byte returnTypeId, byte continuationTypeId) {
        ArrayList<Node> nestedControlTable = new ArrayList<>();
        ArrayList<Node> callNodes = new ArrayList<>();
        int startStackSize = state.stackSize();
        int startOffset = offset();
        int startByteConstantOffset = state.byteConstantOffset();
        int startIntConstantOffset = state.intConstantOffset();
        int startLongConstantOffset = state.longConstantOffset();
        int startBranchTableOffset = state.branchTableOffset();
        WasmBlockNode currentBlock = new WasmBlockNode(module, codeEntry, startOffset, returnTypeId, continuationTypeId, startStackSize,
                        startByteConstantOffset, startIntConstantOffset, startLongConstantOffset, startBranchTableOffset);

        // Push the type length of the current block's continuation.
        // Used when branching out of nested blocks (br and br_if instructions).
        state.pushContinuationReturnLength(currentBlock.continuationTypeLength());

        int opcode;
        do {
            opcode = read1() & 0xFF;
            switch (opcode) {
                case Instructions.UNREACHABLE:
                    break;
                case Instructions.NOP:
                    break;
                case Instructions.BLOCK: {
                    // Save the current block's stack pointer, in case we branch out of
                    // the nested block (continuation stack pointer).
                    state.pushStackState(state.stackSize());
                    WasmBlockNode nestedBlock = readBlock(codeEntry, state);
                    nestedControlTable.add(nestedBlock);
                    state.popStackState();
                    break;
                }
                case Instructions.LOOP: {
                    // Save the current block's stack pointer, in case we branch out of
                    // the nested block (continuation stack pointer).
                    state.pushStackState(state.stackSize());
                    LoopNode loopBlock = readLoop(codeEntry, state);
                    nestedControlTable.add(loopBlock);
                    state.popStackState();
                    break;
                }
                case Instructions.IF: {
                    // Save the current block's stack pointer, in case we branch out of
                    // the nested block (continuation stack pointer).
                    // For the if block, we save the stack size reduced by 1, because of the
                    // condition value that will be popped before executing the if statement.
                    state.pushStackState(state.stackSize() - 1);
                    WasmIfNode ifNode = readIf(codeEntry, state);
                    nestedControlTable.add(ifNode);
                    state.popStackState();
                    break;
                }
                case Instructions.ELSE:
                    break;
                case Instructions.END:
                    break;
                case Instructions.BR: {
                    // TODO: restore check
                    // This check was here to validate the stack size before branching and make sure
                    // that the block that is currently executing produced as many values as it
                    // was meant to before branching.
                    // We now have to postpone this check, as the target of a branch instruction may
                    // be more than one levels up, so the amount of values it should leave in
                    // the stack depends on the branch target.
                    // Assert.assertEquals(state.stackSize() - startStackSize,
                    // currentBlock.returnTypeLength(), "Invalid stack state on BR instruction");
                    int unwindLevel = readLabelIndex(bytesConsumed);
                    state.useLongConstant(unwindLevel);
                    state.useByteConstant(bytesConsumed[0]);
                    state.useIntConstant(state.getStackState(unwindLevel));
                    state.useIntConstant(state.getContinuationReturnLength(unwindLevel));
                    break;
                }
                case Instructions.BR_IF: {
                    state.pop();  // The branch condition.
                    // TODO: restore check
                    // This check was here to validate the stack size before branching and make sure
                    // that the block that is currently executing produced as many values as it
                    // was meant to before branching.
                    // We now have to postpone this check, as the target of a branch instruction may
                    // be more than one levels up, so the amount of values it should leave in the
                    // stack depends on the branch target.
                    // Assert.assertEquals(state.stackSize() - startStackSize,
                    // currentBlock.returnTypeLength(), "Invalid stack state on BR instruction");
                    int unwindLevel = readLabelIndex(bytesConsumed);
                    state.useLongConstant(unwindLevel);
                    state.useByteConstant(bytesConsumed[0]);
                    state.useIntConstant(state.getStackState(unwindLevel));
                    state.useIntConstant(state.getContinuationReturnLength(unwindLevel));
                    break;
                }
                case Instructions.BR_TABLE: {
                    int numLabels = readVectorLength();
                    // We need to save three tables here, to maintain the mapping target -> state
                    // mapping:
                    // - a table containing the branch targets for the instruction
                    // - a table containing the stack state for each corresponding branch target
                    // - the length of the return type
                    // We encode this in a single array.
                    int[] branchTable = new int[2 * (numLabels + 1) + 1];
                    int returnLength = -1;
                    // The BR_TABLE instruction behaves like a 'switch' statement.
                    // There is one extra label for the 'default' case.
                    for (int i = 0; i != numLabels + 1; ++i) {
                        final int targetLabel = readLabelIndex();
                        branchTable[1 + 2 * i + 0] = targetLabel;
                        branchTable[1 + 2 * i + 1] = state.getStackState(targetLabel);
                        final int blockReturnLength = state.getContinuationReturnLength(targetLabel);
                        if (returnLength == -1) {
                            returnLength = blockReturnLength;
                        } else {
                            Assert.assertIntEqual(returnLength, blockReturnLength,
                                            "All target blocks in br.table must have the same return type length.");
                        }
                    }
                    branchTable[0] = returnLength;
                    // TODO: Maybe move this pop up for consistency.
                    state.pop();
                    // The offset to the branch table.
                    state.saveBranchTable(branchTable);
                    break;
                }
                case Instructions.RETURN: {
                    state.useLongConstant(state.stackStateCount());
                    state.useIntConstant(state.getRootBlockReturnLength());
                    break;
                }
                case Instructions.CALL: {
                    int functionIndex = readFunctionIndex(bytesConsumed);
                    state.useLongConstant(functionIndex);
                    state.useByteConstant(bytesConsumed[0]);
                    WasmFunction function = module.symbolTable().function(functionIndex);
                    state.pop(function.numArguments());
                    state.push(function.returnTypeLength());

                    // We deliberately do not create the call node during parsing,
                    // because the call target is only created after the code entry is parsed.
                    // The code entry might not be yet parsed when we encounter this call.
                    //
                    // Furthermore, if the call target is imported from another module,
                    // then that other module might not have been parsed yet.
                    // Therefore, the call node must be created lazily,
                    // i.e. during the first execution.
                    // Therefore, we store the WasmFunction the corresponding index,
                    // which is replaced with the call node during the first execution.
                    callNodes.add(new WasmCallStubNode(function));

                    break;
                }
                case Instructions.CALL_INDIRECT: {
                    int expectedFunctionTypeIndex = readTypeIndex(bytesConsumed);
                    state.useLongConstant(expectedFunctionTypeIndex);
                    state.useByteConstant(bytesConsumed[0]);
                    int numArguments = module.symbolTable().functionTypeArgumentCount(expectedFunctionTypeIndex);
                    int returnLength = module.symbolTable().functionTypeReturnTypeLength(expectedFunctionTypeIndex);

                    // Pop the function index to call, then pop the arguments and push the return
                    // value.
                    state.pop();
                    state.pop(numArguments);
                    state.push(returnLength);
                    callNodes.add(WasmIndirectCallNode.create());
                    Assert.assertIntEqual(read1(), CallIndirect.ZERO_TABLE, "CALL_INDIRECT: Instruction must end with 0x00");
                    break;
                }
                case Instructions.DROP:
                    state.pop();
                    break;
                case Instructions.SELECT:
                    // Pop three values from the stack: the condition and the values to select
                    // between.
                    state.pop(3);
                    state.push();
                    break;
                case Instructions.LOCAL_GET: {
                    int localIndex = readLocalIndex(bytesConsumed);
                    state.useLongConstant(localIndex);
                    state.useByteConstant(bytesConsumed[0]);
                    // Assert localIndex exists.
                    Assert.assertIntLessOrEqual(localIndex, codeEntry.numLocals(), "Invalid local index for local.get");
                    state.push();
                    break;
                }
                case Instructions.LOCAL_SET: {
                    int localIndex = readLocalIndex(bytesConsumed);
                    state.useLongConstant(localIndex);
                    state.useByteConstant(bytesConsumed[0]);
                    // Assert localIndex exists.
                    Assert.assertIntLessOrEqual(localIndex, codeEntry.numLocals(), "Invalid local index for local.set");
                    // Assert there is a value on the top of the stack.
                    Assert.assertIntGreater(state.stackSize(), 0, "local.set requires at least one element in the stack");
                    state.pop();
                    break;
                }
                case Instructions.LOCAL_TEE: {
                    int localIndex = readLocalIndex(bytesConsumed);
                    state.useLongConstant(localIndex);
                    state.useByteConstant(bytesConsumed[0]);
                    // Assert localIndex exists.
                    Assert.assertIntLessOrEqual(localIndex, codeEntry.numLocals(), "Invalid local index for local.tee");
                    // Assert there is a value on the top of the stack.
                    Assert.assertIntGreater(state.stackSize(), 0, "local.tee requires at least one element in the stack");
                    break;
                }
                case Instructions.GLOBAL_GET: {
                    int index = readLocalIndex(bytesConsumed);
                    state.useLongConstant(index);
                    state.useByteConstant(bytesConsumed[0]);
                    Assert.assertIntLessOrEqual(index, module.symbolTable().maxGlobalIndex(),
                                    "Invalid global index for global.get.");
                    state.push();
                    break;
                }
                case Instructions.GLOBAL_SET: {
                    int index = readLocalIndex(bytesConsumed);
                    state.useLongConstant(index);
                    state.useByteConstant(bytesConsumed[0]);
                    // Assert localIndex exists.
                    Assert.assertIntLessOrEqual(index, module.symbolTable().maxGlobalIndex(),
                                    "Invalid global index for global.set.");
                    // Assert that the global is mutable.
                    Assert.assertTrue(module.symbolTable().globalMutability(index) == GlobalModifier.MUTABLE,
                                    "Immutable globals cannot be set: " + index);
                    // Assert there is a value on the top of the stack.
                    Assert.assertIntGreater(state.stackSize(), 0, "global.set requires at least one element in the stack");
                    state.pop();
                    break;
                }
                case Instructions.I32_LOAD:
                case Instructions.I64_LOAD:
                case Instructions.F32_LOAD:
                case Instructions.F64_LOAD:
                case Instructions.I32_LOAD8_S:
                case Instructions.I32_LOAD8_U:
                case Instructions.I32_LOAD16_S:
                case Instructions.I32_LOAD16_U:
                case Instructions.I64_LOAD8_S:
                case Instructions.I64_LOAD8_U:
                case Instructions.I64_LOAD16_S:
                case Instructions.I64_LOAD16_U:
                case Instructions.I64_LOAD32_S:
                case Instructions.I64_LOAD32_U: {
                    // We don't store the `align` literal, as our implementation does not make use
                    // of it, but we need to store it's byte length, so that we can skip it
                    // during execution.
                    readUnsignedInt32(bytesConsumed);
                    // Set consume count for the bytes.
                    state.useByteConstant(bytesConsumed[0]);
                    int loadOffset = readUnsignedInt32(bytesConsumed);
                    state.useLongConstant(loadOffset);
                    state.useByteConstant(bytesConsumed[0]);
                    Assert.assertIntGreater(state.stackSize(), 0, String.format("load instruction 0x%02X requires at least one element in the stack", opcode));
                    state.pop();   // Base address.
                    state.push();  // Loaded value.
                    break;
                }
                case Instructions.I32_STORE:
                case Instructions.I64_STORE:
                case Instructions.F32_STORE:
                case Instructions.F64_STORE:
                case Instructions.I32_STORE_8:
                case Instructions.I32_STORE_16:
                case Instructions.I64_STORE_8:
                case Instructions.I64_STORE_16:
                case Instructions.I64_STORE_32: {
                    readUnsignedInt32(bytesConsumed);  // align
                    // We don't store the `align` literal, as our implementation does not make use
                    // of it,but we need to store it's byte length, so that we can skip it
                    // during the execution.
                    state.useByteConstant(bytesConsumed[0]);
                    int storeOffset = readUnsignedInt32(bytesConsumed);
                    state.useLongConstant(storeOffset);
                    state.useByteConstant(bytesConsumed[0]);
                    Assert.assertIntGreater(state.stackSize(), 1, String.format("store instruction 0x%02X requires at least two elements in the stack", opcode));
                    state.pop();  // Value to store.
                    state.pop();  // Base address.
                    break;
                }
                case Instructions.MEMORY_SIZE: {
                    // Skip the constant 0x00.
                    read1();
                    state.push();
                    break;
                }
                case Instructions.MEMORY_GROW: {
                    // Skip the constant 0x00.
                    read1();
                    state.pop();
                    state.push();
                    break;
                }
                case Instructions.I32_CONST: {
                    int value = readSignedInt32(bytesConsumed);
                    state.useLongConstant(value);
                    state.useByteConstant(bytesConsumed[0]);
                    state.push();
                    break;
                }
                case Instructions.I64_CONST: {
                    long value = readSignedInt64(bytesConsumed);
                    state.useLongConstant(value);
                    state.useByteConstant(bytesConsumed[0]);
                    state.push();
                    break;
                }
                case Instructions.F32_CONST: {
                    int value = readFloatAsInt32();
                    state.useLongConstant(value);
                    state.push();
                    break;
                }
                case Instructions.F64_CONST: {
                    long value = readFloatAsInt64();
                    state.useLongConstant(value);
                    state.push();
                    break;
                }
                case Instructions.I32_EQZ:
                    state.pop();
                    state.push();
                    break;
                case Instructions.I32_EQ:
                case Instructions.I32_NE:
                case Instructions.I32_LT_S:
                case Instructions.I32_LT_U:
                case Instructions.I32_GT_S:
                case Instructions.I32_GT_U:
                case Instructions.I32_LE_S:
                case Instructions.I32_LE_U:
                case Instructions.I32_GE_S:
                case Instructions.I32_GE_U:
                    state.pop();
                    state.pop();
                    state.push();
                    break;
                case Instructions.I64_EQZ:
                    state.pop();
                    state.push();
                    break;
                case Instructions.I64_EQ:
                case Instructions.I64_NE:
                case Instructions.I64_LT_S:
                case Instructions.I64_LT_U:
                case Instructions.I64_GT_S:
                case Instructions.I64_GT_U:
                case Instructions.I64_LE_S:
                case Instructions.I64_LE_U:
                case Instructions.I64_GE_S:
                case Instructions.I64_GE_U:
                    state.pop();
                    state.pop();
                    state.push();
                    break;
                case Instructions.F32_EQ:
                case Instructions.F32_NE:
                case Instructions.F32_LT:
                case Instructions.F32_GT:
                case Instructions.F32_LE:
                case Instructions.F32_GE:
                    state.pop();
                    state.pop();
                    state.push();
                    break;
                case Instructions.F64_EQ:
                case Instructions.F64_NE:
                case Instructions.F64_LT:
                case Instructions.F64_GT:
                case Instructions.F64_LE:
                case Instructions.F64_GE:
                    state.pop();
                    state.pop();
                    state.push();
                    break;
                case Instructions.I32_CLZ:
                case Instructions.I32_CTZ:
                case Instructions.I32_POPCNT:
                    state.pop();
                    state.push();
                    break;
                case Instructions.I32_ADD:
                case Instructions.I32_SUB:
                case Instructions.I32_MUL:
                case Instructions.I32_DIV_S:
                case Instructions.I32_DIV_U:
                case Instructions.I32_REM_S:
                case Instructions.I32_REM_U:
                case Instructions.I32_AND:
                case Instructions.I32_OR:
                case Instructions.I32_XOR:
                case Instructions.I32_SHL:
                case Instructions.I32_SHR_S:
                case Instructions.I32_SHR_U:
                case Instructions.I32_ROTL:
                case Instructions.I32_ROTR:
                    state.pop();
                    state.pop();
                    state.push();
                    break;
                case Instructions.I64_CLZ:
                case Instructions.I64_CTZ:
                case Instructions.I64_POPCNT:
                    state.pop();
                    state.push();
                    break;
                case Instructions.I64_ADD:
                case Instructions.I64_SUB:
                case Instructions.I64_MUL:
                case Instructions.I64_DIV_S:
                case Instructions.I64_DIV_U:
                case Instructions.I64_REM_S:
                case Instructions.I64_REM_U:
                case Instructions.I64_AND:
                case Instructions.I64_OR:
                case Instructions.I64_XOR:
                case Instructions.I64_SHL:
                case Instructions.I64_SHR_S:
                case Instructions.I64_SHR_U:
                case Instructions.I64_ROTL:
                case Instructions.I64_ROTR:
                    state.pop();
                    state.pop();
                    state.push();
                    break;
                case Instructions.F32_ABS:
                case Instructions.F32_NEG:
                case Instructions.F32_CEIL:
                case Instructions.F32_FLOOR:
                case Instructions.F32_TRUNC:
                case Instructions.F32_NEAREST:
                case Instructions.F32_SQRT:
                    state.pop();
                    state.push();
                    break;
                case Instructions.F32_ADD:
                case Instructions.F32_SUB:
                case Instructions.F32_MUL:
                case Instructions.F32_DIV:
                case Instructions.F32_MIN:
                case Instructions.F32_MAX:
                case Instructions.F32_COPYSIGN:
                    state.pop();
                    state.pop();
                    state.push();
                    break;
                case Instructions.F64_ABS:
                case Instructions.F64_NEG:
                case Instructions.F64_CEIL:
                case Instructions.F64_FLOOR:
                case Instructions.F64_TRUNC:
                case Instructions.F64_NEAREST:
                case Instructions.F64_SQRT:
                    state.pop();
                    state.push();
                    break;
                case Instructions.F64_ADD:
                case Instructions.F64_SUB:
                case Instructions.F64_MUL:
                case Instructions.F64_DIV:
                case Instructions.F64_MIN:
                case Instructions.F64_MAX:
                case Instructions.F64_COPYSIGN:
                    state.pop();
                    state.pop();
                    state.push();
                    break;
                case Instructions.I32_WRAP_I64:
                case Instructions.I32_TRUNC_F32_S:
                case Instructions.I32_TRUNC_F32_U:
                case Instructions.I32_TRUNC_F64_S:
                case Instructions.I32_TRUNC_F64_U:
                case Instructions.I64_EXTEND_I32_S:
                case Instructions.I64_EXTEND_I32_U:
                case Instructions.I64_TRUNC_F32_S:
                case Instructions.I64_TRUNC_F32_U:
                case Instructions.I64_TRUNC_F64_S:
                case Instructions.I64_TRUNC_F64_U:
                case Instructions.F32_CONVERT_I32_S:
                case Instructions.F32_CONVERT_I32_U:
                case Instructions.F32_CONVERT_I64_S:
                case Instructions.F32_CONVERT_I64_U:
                case Instructions.F32_DEMOTE_F64:
                case Instructions.F64_CONVERT_I32_S:
                case Instructions.F64_CONVERT_I32_U:
                case Instructions.F64_CONVERT_I64_S:
                case Instructions.F64_CONVERT_I64_U:
                case Instructions.F64_PROMOTE_F32:
                case Instructions.I32_REINTERPRET_F32:
                case Instructions.I64_REINTERPRET_F64:
                case Instructions.F32_REINTERPRET_I32:
                case Instructions.F64_REINTERPRET_I64:
                    state.pop();
                    state.push();
                    break;
                default:
                    Assert.fail(Assert.format("Unknown opcode: 0x%02x", opcode));
                    break;
            }
        } while (opcode != Instructions.END && opcode != Instructions.ELSE);
        currentBlock.initialize(nestedControlTable.toArray(new Node[nestedControlTable.size()]),
                        callNodes.toArray(new Node[callNodes.size()]),
                        offset() - startOffset, state.byteConstantOffset() - startByteConstantOffset,
                        state.intConstantOffset() - startIntConstantOffset, state.longConstantOffset() - startLongConstantOffset,
                        state.branchTableOffset() - startBranchTableOffset);
        // TODO: Restore this check, when we fix the case where the block contains a return
        // instruction.
        // checkValidStateOnBlockExit(returnTypeId, state, startStackSize);

        // Pop the current block return length in the return lengths stack.
        // Used when branching out of nested blocks (br and br_if instructions).
        state.popContinuationReturnLength();

        return currentBlock;
    }

    private LoopNode readLoop(WasmCodeEntry codeEntry, ExecutionState state, byte returnTypeId) {
        int initialStackPointer = state.stackSize();
        WasmBlockNode loopBlock = readBlockBody(codeEntry, state, returnTypeId, ValueTypes.VOID_TYPE);

        // TODO: Hack to correctly set the stack pointer for abstract interpretation.
        // If a block has branch instructions that target "shallower" blocks which return no value,
        // then it can leave no values in the stack, which is invalid for our abstract
        // interpretation.
        // Correct the stack pointer to the value it would have in case there were no branch
        // instructions.
        state.setStackPointer(returnTypeId != ValueTypes.VOID_TYPE ? initialStackPointer + 1 : initialStackPointer);

        return Truffle.getRuntime().createLoopNode(loopBlock);
    }

    private WasmIfNode readIf(WasmCodeEntry codeEntry, ExecutionState state) {
        byte blockTypeId = readBlockType();
        int initialStackPointer = state.stackSize();

        // Pop the condition value from the stack.
        state.pop();

        // Read true branch.
        int startOffset = offset();
        WasmBlockNode trueBranchBlock = readBlockBody(codeEntry, state, blockTypeId, blockTypeId);

        // TODO: Hack to correctly set the stack pointer for abstract interpretation.
        // If a block has branch instructions that target "shallower" blocks which return no value,
        // then it can leave no values in the stack, which is invalid for our abstract
        // interpretation.
        // Correct the stack pointer to the value it would have in case there were no branch
        // instructions.
        state.setStackPointer(blockTypeId != ValueTypes.VOID_TYPE ? initialStackPointer : initialStackPointer - 1);

        // Read false branch, if it exists.
        WasmNode falseBranchBlock;
        if (peek1(-1) == Instructions.ELSE) {
            // If the if instruction has a true and a false branch, and it has non-void type, then
            // each one of the two
            // readBlockBody above and below would push once, hence we need to pop once to
            // compensate for the extra push.
            if (blockTypeId != ValueTypes.VOID_TYPE) {
                state.pop();
            }

            falseBranchBlock = readBlockBody(codeEntry, state, blockTypeId, blockTypeId);

            if (blockTypeId != ValueTypes.VOID_TYPE) {
                // TODO: Hack to correctly set the stack pointer for abstract interpretation.
                // If a block has branch instructions that target "shallower" blocks which return no
                // value, then it can leave no values in the stack, which is invalid for our
                // abstract interpretation.
                // Correct the stack pointer to the value it would have in case there were no branch
                // instructions.
                state.setStackPointer(initialStackPointer);
            }
        } else {
            if (blockTypeId != ValueTypes.VOID_TYPE) {
                Assert.fail("An if statement without an else branch block cannot return values.");
            }
            falseBranchBlock = new WasmEmptyNode(module, codeEntry, 0);
        }

        return new WasmIfNode(module, codeEntry, trueBranchBlock, falseBranchBlock, offset() - startOffset, blockTypeId, initialStackPointer);
    }

    private void readElementSection() {
        final WasmContext context = WasmLanguage.getCurrentContext();
        int numElements = readVectorLength();
        for (int i = 0; i != numElements; ++i) {
            int tableIndex = readUnsignedInt32();
            // At the moment, WebAssembly only supports one table instance, thus the only valid
            // table index is 0.
            Assert.assertIntEqual(tableIndex, 0, "Invalid table index");

            // Read the offset expression.
            byte instruction = read1();
            // Table offset expression must be a constant expression with result type i32.
            // https://webassembly.github.io/spec/core/syntax/modules.html#element-segments
            // https://webassembly.github.io/spec/core/valid/instructions.html#constant-expressions
            switch (instruction) {
                case Instructions.I32_CONST: {
                    int elementOffset = readSignedInt32();
                    readEnd();
                    // Read the contents.
                    int[] contents = readElemContents();
                    module.symbolTable().initializeTableWithFunctions(context, elementOffset, contents);
                    break;
                }
                case Instructions.GLOBAL_GET: {
                    int index = readGlobalIndex();
                    readEnd();
                    int[] contents = readElemContents();
                    final Linker linker = context.linker();
                    linker.tryInitializeElements(context, module, index, contents);
                    break;
                }
                default: {
                    Assert.fail(String.format("Invalid instruction for table offset expression: 0x%02X", instruction));
                }
            }
        }
    }

    private void readEnd() {
        byte instruction = read1();
        Assert.assertByteEqual(instruction, (byte) Instructions.END, "Initialization expression must end with an END");
    }

    private int[] readElemContents() {
        int contentLength = readUnsignedInt32();
        int[] contents = new int[contentLength];
        for (int funcIdx = 0; funcIdx != contentLength; ++funcIdx) {
            contents[funcIdx] = readFunctionIndex();
        }
        return contents;
    }

    private void readStartSection() {
        int startFunctionIndex = readFunctionIndex();
        module.symbolTable().setStartFunction(startFunctionIndex);
    }

    private void readExportSection() {
        int numExports = readVectorLength();
        for (int i = 0; i != numExports; ++i) {
            String exportName = readName();
            byte exportType = readExportType();
            switch (exportType) {
                case ExportIdentifier.FUNCTION: {
                    int functionIndex = readFunctionIndex();
                    module.symbolTable().exportFunction(exportName, functionIndex);
                    break;
                }
                case ExportIdentifier.TABLE: {
                    int tableIndex = readTableIndex();
                    Assert.assertTrue(module.symbolTable().tableExists(), "No table was imported or declared, so cannot export a table");
                    Assert.assertIntEqual(tableIndex, 0, "Cannot export table index different than zero (only one table per module allowed)");
                    module.symbolTable().exportTable(exportName);
                    break;
                }
                case ExportIdentifier.MEMORY: {
                    readMemoryIndex();
                    // TODO: Store the export information somewhere (e.g. in the symbol table).
                    break;
                }
                case ExportIdentifier.GLOBAL: {
                    int index = readGlobalIndex();
                    module.symbolTable().exportGlobal(exportName, index);
                    break;
                }
                default: {
                    Assert.fail(String.format("Invalid export type identifier: 0x%02X", exportType));
                }
            }
        }
    }

    private void readGlobalSection() {
        final Globals globals = WasmLanguage.getCurrentContext().globals();
        int numGlobals = readVectorLength();
        int startingGlobalIndex = module.symbolTable().maxGlobalIndex() + 1;
        for (int i = startingGlobalIndex; i != startingGlobalIndex + numGlobals; i++) {
            byte type = readValueType();
            // 0x00 means const, 0x01 means var
            byte mutability = read1();
            long value = 0;
            GlobalResolution resolution;
            int existingIndex = -1;
            byte instruction = read1();
            // Global initialization expressions must be constant expressions:
            // https://webassembly.github.io/spec/core/valid/instructions.html#constant-expressions
            switch (instruction) {
                case Instructions.I32_CONST:
                    value = readSignedInt32();
                    resolution = GlobalResolution.DECLARED;
                    break;
                case Instructions.I64_CONST:
                    value = readSignedInt64();
                    resolution = GlobalResolution.DECLARED;
                    break;
                case Instructions.F32_CONST:
                    value = readFloatAsInt32();
                    resolution = GlobalResolution.DECLARED;
                    break;
                case Instructions.F64_CONST:
                    value = readFloatAsInt64();
                    resolution = GlobalResolution.DECLARED;
                    break;
                case Instructions.GLOBAL_GET:
                    existingIndex = readGlobalIndex();
                    final GlobalResolution existingResolution = module.symbolTable().globalResolution(existingIndex);
                    Assert.assertTrue(existingResolution.isImported(),
                                    String.format("Global %d is not initialized with an imported global.", i));
                    if (existingResolution.isResolved()) {
                        final byte existingType = module.symbolTable().globalValueType(existingIndex);
                        Assert.assertByteEqual(type, existingType,
                                        String.format("The types of the globals must be consistent: 0x%02X vs 0x%02X", type, existingType));
                        final int existingAddress = module.symbolTable().globalAddress(existingIndex);
                        value = globals.loadAsLong(existingAddress);
                        resolution = GlobalResolution.DECLARED;
                    } else {
                        // The imported module with the referenced global was not yet parsed and
                        // resolved,
                        // so it is not possible to initialize the current global.
                        // The resolution state is set accordingly, until it gets resolved later.
                        resolution = GlobalResolution.UNRESOLVED_GET;
                    }
                    break;
                default:
                    throw Assert.fail(String.format("Invalid instruction for global initialization: 0x%02X", instruction));
            }
            instruction = read1();
            Assert.assertByteEqual(instruction, (byte) Instructions.END, "Global initialization must end with END");
            final int address = module.symbolTable().declareGlobal(WasmLanguage.getCurrentContext(), i, type, mutability, resolution);
            if (resolution.isResolved()) {
                globals.storeLong(address, value);
            } else {
                module.symbolTable().trackUnresolvedGlobal(i, existingIndex);
            }
        }
    }

    private void readDataSection() {
        WasmMemory memory = module.symbolTable().memory();
        Assert.assertNotNull(memory, "No memory declared or imported in the module.");
        int numDataSections = readVectorLength();
        for (int i = 0; i != numDataSections; ++i) {
            int memIndex = readUnsignedInt32();
            // At the moment, WebAssembly only supports one memory instance, thus the only valid
            // memory index is 0.
            Assert.assertIntEqual(memIndex, 0, "Invalid memory index, only the memory index 0 is currently supported");
            long dataOffset = 0;
            byte instruction;
            do {
                instruction = read1();

                // Data dataOffset expression must be a constant expression with result type i32.
                // https://webassembly.github.io/spec/core/syntax/modules.html#data-segments
                // https://webassembly.github.io/spec/core/valid/instructions.html#constant-expressions

                switch (instruction) {
                    case Instructions.I32_CONST:
                        dataOffset = readSignedInt32();
                        break;
                    case Instructions.GLOBAL_GET:
                        readGlobalIndex();
                        // TODO: Implement GLOBAL_GET case for data sections (and add tests).
                        throw new WasmException("GLOBAL_GET in data section not implemented.");
                        // dataOffset = module.globals().getAsInt(index);
                        // break;
                    case Instructions.END:
                        break;
                    default:
                        Assert.fail(String.format("Invalid instruction for data offset expression: 0x%02X", instruction));
                }
            } while (instruction != Instructions.END);
            int byteLength = readVectorLength();

            long baseAddress = dataOffset;
            memory.validateAddress(baseAddress, byteLength);

            for (int writeOffset = 0; writeOffset != byteLength; ++writeOffset) {
                byte b = read1();
                memory.store_i32_8(baseAddress + writeOffset, b);
            }
        }
    }

    private void readFunctionType() {
        int paramsLength = readVectorLength();
        int resultLength = peekUnsignedInt32(paramsLength);
        resultLength = (resultLength == 0x40) ? 0 : resultLength;
        int idx = module.symbolTable().allocateFunctionType(paramsLength, resultLength);
        readParameterList(idx, paramsLength);
        readResultList(idx);
    }

    private void readParameterList(int funcTypeIdx, int numParams) {
        for (int paramIdx = 0; paramIdx != numParams; ++paramIdx) {
            byte type = readValueType();
            module.symbolTable().registerFunctionTypeParameterType(funcTypeIdx, paramIdx, type);
        }
    }

    // Specification seems ambiguous:
    // https://webassembly.github.io/spec/core/binary/types.html#result-types
    // According to the spec, the result type can only be 0x40 (void) or 0xtt, where tt is a value
    // type.
    // However, the Wasm binary compiler produces binaries with either 0x00 or 0x01 0xtt. Therefore,
    // we support both.
    private void readResultList(int funcTypeIdx) {
        byte b = read1();
        switch (b) {
            case ValueTypes.VOID_TYPE:  // special byte indicating empty return type (same as above)
                break;
            case 0x00:  // empty vector
                break;
            case 0x01:  // vector with one element (produced by the Wasm binary compiler)
                byte type = readValueType();
                module.symbolTable().registerFunctionTypeReturnType(funcTypeIdx, 0, type);
                break;
            default:
                Assert.fail(String.format("Invalid return value specifier: 0x%02X", b));
        }
    }

    private boolean isEOF() {
        return offset == data.length;
    }

    private int readVectorLength() {
        return readUnsignedInt32();
    }

    private int readFunctionIndex() {
        return readUnsignedInt32();
    }

    private int readTypeIndex() {
        return readUnsignedInt32();
    }

    private int readTypeIndex(byte[] bytesConsumedResult) {
        return readUnsignedInt32(bytesConsumedResult);
    }

    private int readFunctionIndex(byte[] bytesConsumedResult) {
        return readUnsignedInt32(bytesConsumedResult);
    }

    private int readTableIndex() {
        return readUnsignedInt32();
    }

    private int readMemoryIndex() {
        return readUnsignedInt32();
    }

    private int readGlobalIndex() {
        return readUnsignedInt32();
    }

    @SuppressWarnings("unused")
    private int readLocalIndex() {
        return readUnsignedInt32();
    }

    private int readLocalIndex(byte[] bytesConsumedResult) {
        return readUnsignedInt32(bytesConsumedResult);
    }

    private int readLabelIndex() {
        return readUnsignedInt32();
    }

    private int readLabelIndex(byte[] bytesConsumedResult) {
        return readUnsignedInt32(bytesConsumedResult);
    }

    private byte readExportType() {
        return read1();
    }

    private byte readImportType() {
        return read1();
    }

    private byte readElemType() {
        return read1();
    }

    private byte readLimitsPrefix() {
        return read1();
    }

    private String readName() {
        int nameLength = readVectorLength();
        byte[] name = new byte[nameLength];
        for (int i = 0; i != nameLength; ++i) {
            name[i] = read1();
        }
        return new String(name, StandardCharsets.US_ASCII);
    }

    private boolean tryJumpToSection(int targetSectionId) {
        offset = 0;
        validateMagicNumberAndVersion();
        while (!isEOF()) {
            byte sectionID = read1();
            int size = readUnsignedInt32();
            if (sectionID == targetSectionId) {
                return true;
            }
            offset += size;
        }
        return false;
    }

    /**
     * Reset the state of the globals in a module that had already been parsed and linked.
     */
    @SuppressWarnings("unused")
    void resetGlobalState() {
        int globalIndex = 0;
        if (tryJumpToSection(Section.IMPORT)) {
            int numImports = readVectorLength();
            for (int i = 0; i != numImports; ++i) {
                String moduleName = readName();
                String memberName = readName();
                byte importType = readImportType();
                switch (importType) {
                    case ImportIdentifier.FUNCTION: {
                        readTableIndex();
                        break;
                    }
                    case ImportIdentifier.TABLE: {
                        readElemType();
                        byte limitsPrefix = read1();
                        switch (limitsPrefix) {
                            case LimitsPrefix.NO_MAX: {
                                readUnsignedInt32();
                                break;
                            }
                            case LimitsPrefix.WITH_MAX: {
                                readUnsignedInt32();
                                readUnsignedInt32();
                                break;
                            }
                        }
                        break;
                    }
                    case ImportIdentifier.MEMORY: {
                        byte limitsPrefix = read1();
                        switch (limitsPrefix) {
                            case LimitsPrefix.NO_MAX: {
                                readUnsignedInt32();
                                break;
                            }
                            case LimitsPrefix.WITH_MAX: {
                                readUnsignedInt32();
                                readUnsignedInt32();
                                break;
                            }
                        }
                        break;
                    }
                    case ImportIdentifier.GLOBAL: {
                        readValueType();
                        byte mutability = read1();
                        if (mutability == GlobalModifier.MUTABLE) {
                            throw new WasmLinkerException("Cannot reset imports of mutable global variables (not implemented).");
                        }
                        globalIndex++;
                        break;
                    }
                    default: {
                        // The module should have been parsed already.
                    }
                }
            }
        }
        if (tryJumpToSection(Section.GLOBAL)) {
            final Globals globals = WasmLanguage.getCurrentContext().globals();
            int numGlobals = readVectorLength();
            int startingGlobalIndex = globalIndex;
            for (; globalIndex != startingGlobalIndex + numGlobals; globalIndex++) {
                readValueType();
                // Read mutability;
                read1();
                byte instruction = read1();
                long value = 0;
                switch (instruction) {
                    case Instructions.I32_CONST: {
                        value = readSignedInt32();
                        break;
                    }
                    case Instructions.I64_CONST: {
                        value = readSignedInt64();
                        break;
                    }
                    case Instructions.F32_CONST: {
                        value = readFloatAsInt32();
                        break;
                    }
                    case Instructions.F64_CONST: {
                        value = readFloatAsInt64();
                        break;
                    }
                    case Instructions.GLOBAL_GET: {
                        int existingIndex = readGlobalIndex();
                        if (module.symbolTable().globalMutability(existingIndex) == GlobalModifier.MUTABLE) {
                            throw new WasmLinkerException("Cannot reset global variables that were initialized " +
                                            "with a non-constant global variable (not implemented).");
                        }
                        final int existingAddress = module.symbolTable().globalAddress(existingIndex);
                        value = globals.loadAsLong(existingAddress);
                        break;
                    }
                }
                // Read END.
                read1();
                final int address = module.symbolTable().globalAddress(globalIndex);
                globals.storeLong(address, value);
            }
        }
    }

    void resetMemoryState(boolean zeroMemory) {
        final WasmMemory memory = module.symbolTable().memory();
        if (memory != null && zeroMemory) {
            memory.clear();
        }
        if (tryJumpToSection(Section.DATA)) {
            readDataSection();
        }
    }
}
