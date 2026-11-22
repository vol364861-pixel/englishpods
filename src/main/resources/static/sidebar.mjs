export function bindSidebar({ root, content, toggle, backdrop, background, narrowScreen, read, write }) {
  const preferenceKey = 'englishpods.sidebar';
  let desktopCollapsed = read(preferenceKey) === 'collapsed';
  let drawerOpen = false;

  function isCollapsed() {
    return narrowScreen.matches ? !drawerOpen : desktopCollapsed;
  }

  function render() {
    const collapsed = isCollapsed();
    const modal = narrowScreen.matches && !collapsed;
    root.classList.toggle('sidebar-collapsed', collapsed);
    root.classList.toggle('sidebar-drawer-open', modal);
    content.hidden = collapsed;
    backdrop.hidden = !modal;
    for (const region of background) region.inert = modal;
    toggle.setAttribute('aria-expanded', String(!collapsed));
    toggle.setAttribute('aria-label', collapsed ? '展开侧边栏' : '收起侧边栏');
    toggle.title = collapsed ? '展开侧边栏' : '收起侧边栏';
  }

  function setCollapsed(value) {
    if (narrowScreen.matches) {
      drawerOpen = !value;
    } else {
      desktopCollapsed = value;
      write(preferenceKey, value ? 'collapsed' : 'expanded');
    }
    render();
  }

  function dismissDrawer() {
    if (!narrowScreen.matches || !drawerOpen) return false;
    setCollapsed(true);
    toggle.focus();
    return true;
  }

  toggle.addEventListener('click', () => setCollapsed(!isCollapsed()));
  backdrop.addEventListener('click', dismissDrawer);
  root.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && dismissDrawer()) event.preventDefault();
  });
  narrowScreen.addEventListener('change', () => {
    drawerOpen = false;
    render();
  });
  render();
  return { expand: () => setCollapsed(false), dismissDrawer };
}
