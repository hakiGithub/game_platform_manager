import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import * as ElementPlusIconsVue from "@element-plus/icons-vue";
import { nextTick } from "vue";
import DeployView from "@/views/instance/deploy.vue";

const api = vi.hoisted(() => ({
  createInstance: vi.fn(),
  checkEnvironment: vi.fn(),
  checkPort: vi.fn(),
  getDeployProgress: vi.fn(),
  getInstanceLogs: vi.fn(),
  getHostList: vi.fn(),
  getHostResources: vi.fn(),
  getGameList: vi.fn(),
  getDeployConfig: vi.fn(),
}));

const routerMocks = vi.hoisted(() => ({
  push: vi.fn(),
  route: { query: {} },
}));

vi.mock("@/api/instance", () => ({
  createInstance: api.createInstance,
  checkEnvironment: api.checkEnvironment,
  checkPort: api.checkPort,
  getDeployProgress: api.getDeployProgress,
  getInstanceLogs: api.getInstanceLogs,
}));

vi.mock("@/api/host", () => ({
  getHostList: api.getHostList,
  getHostResources: api.getHostResources,
}));

vi.mock("@/api/game", () => ({
  getGameList: api.getGameList,
  getDeployConfig: api.getDeployConfig,
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: routerMocks.push }),
  useRoute: () => routerMocks.route,
}));

const iconComponents = Object.fromEntries(
  Object.entries(ElementPlusIconsVue).filter(([, component]) => component),
);

const games = [
  {
    id: 1,
    gameCode: "compose_a",
    gameName: "Compose A",
    supportedDeployTypes: ["docker-compose"],
    deployConfig: { defaultPorts: { game: 7001 } },
    version: "latest",
  },
  {
    id: 2,
    gameCode: "compose_b",
    gameName: "Compose B",
    supportedDeployTypes: ["docker-compose"],
    deployConfig: { defaultPorts: { game: 7002 } },
    version: "latest",
  },
];

function mountView() {
  return mount(DeployView, {
    global: {
      plugins: [ElementPlus],
      components: iconComponents,
      stubs: { DeployProgress: true },
    },
  });
}

function findButton(wrapper, text) {
  return wrapper.findAll("button").find((button) => button.text().includes(text));
}

async function clickButton(wrapper, text) {
  const button = findButton(wrapper, text);
  expect(button, `button "${text}" should exist`).toBeTruthy();
  await button.trigger("click");
  await flushPromises();
}

function findVariableItem(wrapper, label) {
  return wrapper
    .findAll(".deploy-variable-form .el-form-item")
    .find((item) => item.text().includes(label));
}

async function enterVariableStep(wrapper, gameIndex = 0) {
  await wrapper.find(".host-card").trigger("click");
  await clickButton(wrapper, "下一步");
  await wrapper.findAll(".game-item")[gameIndex].trigger("click");
  await clickButton(wrapper, "下一步");
}

describe("instance deploy compose variables", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getHostList.mockResolvedValue({
      records: [{ id: 1, name: "wsl", ip: "192.168.111.253", status: 1 }],
    });
    api.getHostResources.mockResolvedValue(null);
    api.getGameList.mockResolvedValue(games);
    api.checkPort.mockResolvedValue({ available: true });
    api.checkEnvironment.mockResolvedValue({
      passed: true,
      checks: [{ key: "docker", label: "Docker", passed: true, message: "ok" }],
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("keeps edited values across step navigation and reloads when another compose game is selected", async () => {
    api.getDeployConfig.mockImplementation((gameId) =>
      Promise.resolve(
        gameId === 1
          ? {
              variables: [
                {
                  name: "GAME_A_PORT",
                  label: "Game A port",
                  type: "integer",
                  defaultValue: "7101",
                  required: true,
                },
              ],
            }
          : {
              variables: [
                {
                  name: "GAME_B_PORT",
                  label: "Game B port",
                  type: "integer",
                  defaultValue: "7202",
                  required: true,
                },
              ],
            },
      ),
    );

    const wrapper = mountView();
    await flushPromises();
    await enterVariableStep(wrapper);

    const gameAItem = findVariableItem(wrapper, "Game A port");
    expect(gameAItem).toBeTruthy();
    await gameAItem.find("input").setValue("7199");
    await flushPromises();
    expect(gameAItem.find("input").element.value).toBe("7199");

    await clickButton(wrapper, "上一步");
    await clickButton(wrapper, "下一步");
    expect(findVariableItem(wrapper, "Game A port").find("input").element.value).toBe(
      "7199",
    );

    await clickButton(wrapper, "上一步");
    await wrapper.findAll(".game-item")[1].trigger("click");
    await clickButton(wrapper, "下一步");

    expect(api.getDeployConfig).toHaveBeenLastCalledWith(2, "docker-compose");
    expect(findVariableItem(wrapper, "Game A port")).toBeUndefined();
    expect(findVariableItem(wrapper, "Game B port").find("input").element.value).toBe(
      "7202",
    );

    wrapper.unmount();
  });

  it("ignores a stale config response after switching games", async () => {
    let resolveGameA;
    const gameAResponse = new Promise((resolve) => {
      resolveGameA = resolve;
    });
    api.getDeployConfig.mockImplementation((gameId) => {
      if (gameId === 1) return gameAResponse;
      return Promise.resolve({
        variables: [
          {
            name: "GAME_B_PORT",
            label: "Game B port",
            type: "integer",
            defaultValue: "7202",
            required: true,
          },
        ],
      });
    });

    const wrapper = mountView();
    await flushPromises();
    await enterVariableStep(wrapper);

    await clickButton(wrapper, "上一步");
    await wrapper.findAll(".game-item")[1].trigger("click");
    await clickButton(wrapper, "下一步");
    expect(findVariableItem(wrapper, "Game B port").find("input").element.value).toBe(
      "7202",
    );

    resolveGameA({
      variables: [
        {
          name: "GAME_A_PORT",
          label: "Game A port",
          type: "integer",
          defaultValue: "7101",
          required: true,
        },
      ],
    });
    await flushPromises();

    expect(findVariableItem(wrapper, "Game A port")).toBeUndefined();
    expect(findVariableItem(wrapper, "Game B port").find("input").element.value).toBe(
      "7202",
    );

    wrapper.unmount();
  });

  it("blocks moving forward while the new game's variable config is loading", async () => {
    let resolveGameB;
    api.getDeployConfig.mockImplementation((gameId) => {
      if (gameId === 1) {
        return Promise.resolve({
          variables: [
            {
              name: "GAME_A_PORT",
              label: "Game A port",
              type: "integer",
              defaultValue: "7101",
              required: true,
            },
          ],
        });
      }
      return new Promise((resolve) => {
        resolveGameB = resolve;
      });
    });

    const wrapper = mountView();
    await flushPromises();
    await enterVariableStep(wrapper);

    await clickButton(wrapper, "上一步");
    await wrapper.findAll(".game-item")[1].trigger("click");
    await nextTick();

    const loadingForm = findVariableItem(wrapper, "Game A port");
    expect(loadingForm).toBeUndefined();
    await clickButton(wrapper, "下一步");
    expect(findVariableItem(wrapper, "Game B port")).toBeUndefined();

    resolveGameB({
      variables: [
        {
          name: "GAME_B_PORT",
          label: "Game B port",
          type: "integer",
          defaultValue: "7202",
          required: true,
        },
      ],
    });
    await flushPromises();
    expect(findVariableItem(wrapper, "Game B port").find("input").element.value).toBe(
      "7202",
    );

    wrapper.unmount();
  });
});
