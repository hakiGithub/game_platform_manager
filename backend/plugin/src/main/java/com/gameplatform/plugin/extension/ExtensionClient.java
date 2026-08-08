package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 扩展资源客户端，插件唯一的持久化入口。
 * <p>
 * 实例在插件子容器创建时绑定 pluginId，所有方法自动带上身份过滤
 * （{@code group_name} + {@code kind}），插件无法访问其他插件数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ExtensionClient {

    /**
     * 创建资源。框架自动填充 group_name/kind/version=1/时间戳。
     *
     * @param extension 资源对象（name 必填，spec 必填）
     * @param <T>       资源类型
     * @throws com.gameplatform.plugin.extension.exception.DuplicateExtensionException name 已存在
     */
    <T extends AbstractExtension<?>> void create(T extension);

    /**
     * 更新资源（乐观锁）。需传入读到的对象（含 version）。
     *
     * @param extension 资源对象
     * @param <T>       资源类型
     * @throws com.gameplatform.plugin.extension.exception.OptimisticLockException 版本冲突
     * @throws com.gameplatform.plugin.extension.exception.ExtensionNotFoundException 资源不存在
     */
    <T extends AbstractExtension<?>> void update(T extension);

    /**
     * 删除资源。
     *
     * @param modelClass 资源类型
     * @param name       资源名称
     * @param <T>        资源类型
     * @throws com.gameplatform.plugin.extension.exception.ExtensionNotFoundException 资源不存在
     */
    <T extends AbstractExtension<?>> void delete(Class<T> modelClass, String name);

    /**
     * 更新状态字段。
     *
     * @param modelClass 资源类型
     * @param name       资源名称
     * @param status     新状态
     * @param <T>        资源类型
     * @return 更新后的资源对象
     */
    <T extends AbstractExtension<?>> T updateStatus(Class<T> modelClass, String name, String status);

    /**
     * 按 name 获取单个资源。
     *
     * @param modelClass 资源类型
     * @param name       资源名称
     * @param <T>        资源类型
     * @return 资源对象（不存在返回 empty）
     */
    <T extends AbstractExtension<?>> Optional<T> get(Class<T> modelClass, String name);

    /**
     * 按 id 删除资源。
     *
     * @param modelClass 资源类型
     * @param id         资源 ID（雪花ID）
     * @param <T>        资源类型
     * @throws com.gameplatform.plugin.extension.exception.ExtensionNotFoundException 资源不存在
     */
    <T extends AbstractExtension<?>> void deleteById(Class<T> modelClass, String id);

    /**
     * 按 id 更新状态字段。
     *
     * @param modelClass 资源类型
     * @param id         资源 ID（雪花ID）
     * @param status     新状态
     * @param <T>        资源类型
     * @return 更新后的资源对象
     */
    <T extends AbstractExtension<?>> T updateStatusById(Class<T> modelClass, String id, String status);

    /**
     * 按 id 获取单个资源。
     *
     * @param modelClass 资源类型
     * @param id         资源 ID（雪花ID）
     * @param <T>        资源类型
     * @return 资源对象（不存在返回 empty）
     */
    <T extends AbstractExtension<?>> Optional<T> getById(Class<T> modelClass, String id);

    /**
     * 列表查询（带条件）。
     *
     * @param modelClass 资源类型
     * @param opts       查询选项（spec 过滤、label 过滤、分页等）
     * @param <T>        资源类型
     * @return 资源列表
     */
    <T extends AbstractExtension<?>> List<T> list(Class<T> modelClass, ListOptions opts);

    /**
     * 列表查询（无过滤，返回当前插件当前类型的全部资源）。
     *
     * @param modelClass 资源类型
     * @param <T>        资源类型
     * @return 资源列表
     */
    <T extends AbstractExtension<?>> List<T> listAll(Class<T> modelClass);

    /**
     * 计数（带条件）。
     *
     * @param modelClass 资源类型
     * @param opts       查询选项
     * @return 数量
     */
    long count(Class<? extends AbstractExtension<?>> modelClass, ListOptions opts);

    /**
     * 获取当前插件拥有的物理表名（运维/调试用）。
     *
     * @return 表名集合
     */
    Set<String> getManagedTables();
}
