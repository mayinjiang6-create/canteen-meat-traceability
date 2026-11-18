// SPDX-License-Identifier: MIT
pragma solidity ^0.8.11;

/**
 * @title  TraceRegistry
 * @notice 高校食堂肉类供应链溯源系统 · 事件存证合约
 *         链上仅保存 事件标识 / 批次标识 / 事件类型 / 业务时间 / 数据摘要
 *         业务明细留在链下数据库，核验时比对摘要
 * @dev    目标链 FISCO BCOS 3.17（EVM），编译器 solc 0.8.11
 *         设计说明见 docs/tech/04-合约设计.md
 */
contract TraceRegistry {

    uint8 public constant RESULT_APPENDED = 0;
    uint8 public constant RESULT_DUPLICATE = 1;
    uint8 public constant RESULT_CONFLICT  = 2;

    struct EventRecord {
        string  batchId;
        string  eventType;
        uint256 bizTime;
        bytes32 dataDigest;
        address submitter;
        uint256 createdAt;
        bool    exists;
    }

    address public owner;
    uint256 public totalEvents;

    mapping(bytes32 => EventRecord)  private _records;
    mapping(bytes32 => bytes32[])    private _batchIndex;

    event EventAppended(
        bytes32 indexed eventId,
        bytes32 indexed batchKey,
        string  eventType,
        bytes32 dataDigest,
        address submitter,
        uint256 createdAt
    );

    event ConflictDetected(
        bytes32 indexed eventId,
        bytes32 existingDigest,
        bytes32 incomingDigest,
        address submitter
    );

    constructor() {
        owner = msg.sender;
    }

    /**
     * @notice 追加一条事件存证
     * @param  eventId    32 字节事件标识（业务侧生成，全局唯一）
     * @param  batchId    业务批次号 PB* / PR*
     * @param  eventType  事件类型枚举字面量
     * @param  bizTime    业务发生时间（Unix 秒）
     * @param  dataDigest 规范化快照的 SHA-256
     * @return 0=新增 1=幂等（同内容） 2=冲突（异内容，保留原记录）
     */
    function append(
        bytes32 eventId,
        string calldata batchId,
        string calldata eventType,
        uint256 bizTime,
        bytes32 dataDigest
    ) external returns (uint8) {
        require(eventId != bytes32(0),          "eventId required");
        require(dataDigest != bytes32(0),       "dataDigest required");
        require(bytes(batchId).length > 0,      "batchId required");
        require(bytes(eventType).length > 0,    "eventType required");

        EventRecord storage record = _records[eventId];

        if (record.exists) {
            if (record.dataDigest == dataDigest) {
                return RESULT_DUPLICATE;
            }
            emit ConflictDetected(eventId, record.dataDigest, dataDigest, msg.sender);
            return RESULT_CONFLICT;
        }

        record.batchId    = batchId;
        record.eventType  = eventType;
        record.bizTime    = bizTime;
        record.dataDigest = dataDigest;
        record.submitter  = msg.sender;
        record.createdAt  = block.timestamp;
        record.exists     = true;

        bytes32 batchKey = keccak256(abi.encodePacked(batchId));
        _batchIndex[batchKey].push(eventId);
        totalEvents = totalEvents + 1;

        emit EventAppended(eventId, batchKey, eventType, dataDigest, msg.sender, block.timestamp);
        return RESULT_APPENDED;
    }

    /**
     * @notice 按事件标识查询
     */
    function query(bytes32 eventId) external view returns (
        string memory batchId,
        string memory eventType,
        uint256 bizTime,
        bytes32 dataDigest,
        address submitter,
        uint256 createdAt,
        bool    exists
    ) {
        EventRecord storage record = _records[eventId];
        return (
            record.batchId,
            record.eventType,
            record.bizTime,
            record.dataDigest,
            record.submitter,
            record.createdAt,
            record.exists
        );
    }

    /**
     * @notice 某批次的事件数量
     */
    function batchEventCount(string calldata batchId) external view returns (uint256) {
        return _batchIndex[keccak256(abi.encodePacked(batchId))].length;
    }

    /**
     * @notice 按索引取批次下的事件标识
     */
    function batchEventAt(string calldata batchId, uint256 index) external view returns (bytes32) {
        bytes32[] storage list = _batchIndex[keccak256(abi.encodePacked(batchId))];
        require(index < list.length, "index out of range");
        return list[index];
    }

    /**
     * @notice 事件是否存在
     */
    function exists(bytes32 eventId) external view returns (bool) {
        return _records[eventId].exists;
    }
}
