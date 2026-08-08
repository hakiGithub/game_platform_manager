/**
 * game.js API 单元测试
 * 测试游戏列表、新增、更新等接口
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// Mock request 模块
vi.mock("@/utils/request", () => ({
  default: vi.fn(),
}));

describe("game API", () => {
  let request;
  let gameApi;

  beforeEach(async () => {
    vi.clearAllMocks();
    request = (await import("@/utils/request")).default;
    gameApi = await import("@/api/game");
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("getGameList - 获取游戏列表", () => {
    it("应该获取所有游戏列表", async () => {
      const mockResponse = [
        {
          id: 1,
          gameCode: "minecraft",
          gameName: "Minecraft",
          gameIcon: "https://example.com/minecraft.png",
          status: 1,
        },
        {
          id: 2,
          gameCode: "terraria",
          gameName: "Terraria",
          gameIcon: "https://example.com/terraria.png",
          status: 1,
        },
      ];
      request.mockResolvedValue(mockResponse);

      const result = await gameApi.getGameList();

      expect(request).toHaveBeenCalledWith({
        url: "/games/list",
        method: "get",
        params: undefined,
      });
      expect(result).toHaveLength(2);
      expect(result[0].gameCode).toBe("minecraft");
    });

    it("应该使用关键词搜索游戏", async () => {
      const params = { keyword: "mine" };
      const mockResponse = [
        {
          id: 1,
          gameCode: "minecraft",
          gameName: "Minecraft",
          status: 1,
        },
      ];
      request.mockResolvedValue(mockResponse);

      const result = await gameApi.getGameList(params);

      expect(request).toHaveBeenCalledWith({
        url: "/games/list",
        method: "get",
        params,
      });
      expect(result).toHaveLength(1);
    });

    it("应该按状态筛选游戏", async () => {
      const params = { status: 1 };
      request.mockResolvedValue([
        { id: 1, gameCode: "minecraft", status: 1 },
        { id: 2, gameCode: "terraria", status: 1 },
      ]);

      const result = await gameApi.getGameList(params);

      expect(request).toHaveBeenCalledWith(
        expect.objectContaining({
          params: { status: 1 },
        }),
      );
      expect(result.every((g) => g.status === 1)).toBe(true);
    });

    it("应该处理空列表", async () => {
      request.mockResolvedValue([]);

      const result = await gameApi.getGameList();

      expect(result).toEqual([]);
    });

    it("应该处理获取列表失败", async () => {
      request.mockRejectedValue(new Error("网络错误"));

      await expect(gameApi.getGameList()).rejects.toThrow("网络错误");
    });
  });

  describe("getGameDetail - 获取游戏详情", () => {
    it("应该正确获取游戏详情", async () => {
      const mockResponse = {
        id: 1,
        gameCode: "minecraft",
        gameName: "Minecraft",
        gameIcon: "https://example.com/minecraft.png",
        gameDesc: "Minecraft是一款沙盒游戏",
        supportDeployType: '["vanilla", "forge", "fabric"]',
        defaultPort: 25565,
        defaultDependences: '["java17", "screen"]',
        configSchema: '{"type": "object", "properties": {}}',
        customOperations: '[{"name": "备份", "command": "backup"}]',
        metadataFilePath: "/metadata/minecraft.json",
        status: 1,
        createdAt: "2024-01-01T00:00:00Z",
        updatedAt: "2024-01-01T00:00:00Z",
      };
      request.mockResolvedValue(mockResponse);

      const result = await gameApi.getGameDetail(1);

      expect(request).toHaveBeenCalledWith({
        url: "/games/1",
        method: "get",
      });
      expect(result.gameCode).toBe("minecraft");
      expect(result.defaultPort).toBe(25565);
    });

    it("应该处理游戏不存在", async () => {
      request.mockRejectedValue(new Error("游戏不存在"));

      await expect(gameApi.getGameDetail(999)).rejects.toThrow("游戏不存在");
    });
  });

  describe("createGame - 新增游戏", () => {
    it("应该使用完整参数创建游戏", async () => {
      const gameData = {
        gameCode: "valheim",
        gameName: "Valheim",
        gameIcon: "https://example.com/valheim.png",
        gameDesc: "维京题材生存游戏",
        supportDeployType: '["dedicated"]',
        defaultPort: 2456,
        defaultDependences: '["steamcmd"]',
        configSchema: '{"type": "object"}',
        customOperations: "[]",
        metadataFilePath: "/metadata/valheim.json",
        status: 1,
      };
      const mockResponse = { id: 3 };
      request.mockResolvedValue(mockResponse);

      const result = await gameApi.createGame(gameData);

      expect(request).toHaveBeenCalledWith({
        url: "/games",
        method: "post",
        data: gameData,
      });
      expect(result.id).toBe(3);
    });

    it("应该使用最小参数创建游戏", async () => {
      const gameData = {
        gameCode: "rust",
        gameName: "Rust",
        supportDeployType: '["dedicated"]',
        metadataFilePath: "/metadata/rust.json",
      };
      request.mockResolvedValue({ id: 4 });

      const result = await gameApi.createGame(gameData);

      expect(result.id).toBe(4);
    });

    it("应该处理游戏编码已存在", async () => {
      const gameData = {
        gameCode: "minecraft",
        gameName: "Minecraft Duplicate",
        supportDeployType: "[]",
        metadataFilePath: "/metadata/minecraft2.json",
      };
      request.mockRejectedValue(new Error("游戏编码已存在"));

      await expect(gameApi.createGame(gameData)).rejects.toThrow(
        "游戏编码已存在",
      );
    });

    it("应该处理参数验证失败", async () => {
      const gameData = {
        gameCode: "",
        gameName: "",
      };
      request.mockRejectedValue(new Error("游戏编码和名称不能为空"));

      await expect(gameApi.createGame(gameData)).rejects.toThrow(
        "游戏编码和名称不能为空",
      );
    });
  });

  describe("updateGame - 更新游戏", () => {
    it("应该正确更新游戏信息", async () => {
      const updateData = {
        gameName: "Minecraft Updated",
        gameDesc: "更新后的描述",
        defaultPort: 25566,
        status: 1,
      };
      request.mockResolvedValue(null);

      const result = await gameApi.updateGame(1, updateData);

      expect(request).toHaveBeenCalledWith({
        url: "/games/1",
        method: "put",
        data: updateData,
      });
      expect(result).toBeNull();
    });

    it("应该部分更新游戏信息", async () => {
      const updateData = {
        gameIcon: "https://example.com/new-icon.png",
      };
      request.mockResolvedValue(null);

      await gameApi.updateGame(1, updateData);

      expect(request).toHaveBeenCalledWith(
        expect.objectContaining({
          data: { gameIcon: "https://example.com/new-icon.png" },
        }),
      );
    });

    it("应该处理更新不存在的游戏", async () => {
      request.mockRejectedValue(new Error("游戏不存在"));

      await expect(
        gameApi.updateGame(999, { gameName: "test" }),
      ).rejects.toThrow("游戏不存在");
    });

    it("应该处理更新游戏编码冲突", async () => {
      request.mockRejectedValue(new Error("游戏编码已存在"));

      await expect(
        gameApi.updateGame(1, { gameCode: "terraria" }),
      ).rejects.toThrow("游戏编码已存在");
    });
  });

  describe("deleteGame - 删除游戏", () => {
    it("应该正确删除游戏", async () => {
      request.mockResolvedValue(null);

      const result = await gameApi.deleteGame(1);

      expect(request).toHaveBeenCalledWith({
        url: "/games/1",
        method: "delete",
      });
      expect(result).toBeNull();
    });

    it("应该处理删除不存在的游戏", async () => {
      request.mockRejectedValue(new Error("游戏不存在"));

      await expect(gameApi.deleteGame(999)).rejects.toThrow("游戏不存在");
    });

    it("应该处理删除有关联实例的游戏", async () => {
      request.mockRejectedValue(new Error("该游戏下存在实例，无法删除"));

      await expect(gameApi.deleteGame(1)).rejects.toThrow(
        "该游戏下存在实例，无法删除",
      );
    });
  });
});
