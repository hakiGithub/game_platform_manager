/**
 * cloud.js API 单元测试
 * 起始目录浏览/应用均按账号内路径寻址，ID 只作为目录元数据。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

vi.mock("@/utils/request", () => ({
  default: vi.fn(),
}));

describe("cloud API", () => {
  let request;
  let cloudApi;

  beforeEach(async () => {
    vi.clearAllMocks();
    request = (await import("@/utils/request")).default;
    cloudApi = await import("@/api/cloud");
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("listAccountFolders - 浏览账号目录", () => {
    it("uses the requested path and returns id/path metadata without substituting id for value", async () => {
      const mockResponse = {
        path: "/maps",
        rootField: "rootFolderId",
        idBased: true,
        folders: [
          {
            id: "-11",
            path: "/maps/coop",
            value: "/maps/coop",
            name: "coop",
          },
        ],
      };
      request.mockResolvedValue(mockResponse);

      const result = await cloudApi.listAccountFolders("live-189", "/maps");

      expect(request).toHaveBeenCalledWith({
        url: "/cloud/accounts/live-189/folders",
        method: "get",
        params: { path: "/maps" },
      });
      expect(result.folders[0]).toEqual(
        expect.objectContaining({
          id: "-11",
          path: "/maps/coop",
          value: "/maps/coop",
        }),
      );
    });

    it("defaults browsing to the account root path", async () => {
      request.mockResolvedValue({ path: "/", rootField: "rootFolderId", idBased: true, folders: [] });

      await cloudApi.listAccountFolders("live-189");

      expect(request).toHaveBeenCalledWith({
        url: "/cloud/accounts/live-189/folders",
        method: "get",
        params: { path: "/" },
      });
    });
  });

  describe("setAccountRootDir - 应用起始目录", () => {
    it("submits a path value for the backend to resolve provider semantics", async () => {
      request.mockResolvedValue({ name: "live-189", status: "HEALTHY" });

      await cloudApi.setAccountRootDir("live-189", "/maps/coop");

      expect(request).toHaveBeenCalledWith({
        url: "/cloud/accounts/live-189/root-dir",
        method: "post",
        data: { value: "/maps/coop" },
      });
    });
  });
});
