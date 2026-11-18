/**
 * 侧边栏菜单：按**业务流程**分组，不按代码模块分组。
 *
 * 顺序即业务顺序：供应商 → 采购 → 验收 → 加工 → 销售，最后是冷链/异常/召回这类
 * 反向的风险管控动作。答辩演示时从上往下点一遍，就是一条完整的链路。
 */
export interface MenuItem {
  path: string
  title: string
  icon: string
}

export interface MenuGroup {
  title: string
  items: MenuItem[]
}

export const MENU_GROUPS: MenuGroup[] = [
  {
    title: '概览',
    items: [{ path: '/dashboard', title: '运行概览', icon: 'DataBoard' }],
  },
  {
    title: '基础数据',
    items: [{ path: '/suppliers', title: '供应商备案', icon: 'OfficeBuilding' }],
  },
  {
    title: '业务流转',
    items: [
      { path: '/purchases', title: '采购批次', icon: 'ShoppingCart' },
      { path: '/acceptances', title: '入库验收', icon: 'Stamp' },
      { path: '/process', title: '加工投料', icon: 'Cpu' },
      { path: '/sales', title: '销售登记', icon: 'Sell' },
    ],
  },
  {
    title: '风险管控',
    items: [
      { path: '/cold-chain', title: '冷链温控', icon: 'Sunny' },
      { path: '/abnormal', title: '异常管理', icon: 'WarningFilled' },
      { path: '/recalls', title: '批次召回', icon: 'RefreshLeft' },
    ],
  },
]
