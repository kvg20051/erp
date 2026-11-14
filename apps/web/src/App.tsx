import { useEffect, useState } from "react";
import {
  Layout,
  Menu,
  Button,
  Space,
  Typography,
  Select,
  Table,
  Tag,
  Card,
  Statistic,
  Row,
  Col,
  Modal,
  Form,
  Input,
  InputNumber,
  Switch,
  Alert,
  Tabs,
  Descriptions,
  Upload,
  Spin,
  App as AntApp,
  Popconfirm,
  Empty,
} from "antd";
import {
  AppstoreOutlined,
  FileTextOutlined,
  InboxOutlined,
  TeamOutlined,
  CalendarOutlined,
  BarChartOutlined,
  SettingOutlined,
  AuditOutlined,
  PlusOutlined,
  LogoutOutlined,
  ArrowRightOutlined,
  ReloadOutlined,
  DownloadOutlined,
} from "@ant-design/icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api,
  request,
  download,
  isDemo,
  demoLogin,
  manager,
  logout,
} from "./api";
type RowData = Record<string, any>;
const { Title, Text, Paragraph } = Typography;
const kinds: Record<string, string> = {
  REQ: "Заявка на закупку",
  PO: "Заказ поставщику",
  RECEIPT: "Поступление",
  SO: "Заказ клиента",
  INVOICE: "Счёт на оплату",
  SHIPMENT: "Реализация",
  RETURN_IN: "Возврат клиента",
  RETURN_OUT: "Возврат поставщику",
  TRANSFER: "Перемещение",
  WRITE_OFF: "Списание",
  ISSUE: "Выдача сотруднику",
  RETURN_EMP: "Возврат оборудования",
};
const states: Record<string, string> = {
  DRAFT: "Черновик",
  PENDING: "На согласовании",
  APPROVED: "Согласован",
  POSTED: "Проведён",
  CANCELLED: "Отменён",
  ACTIVE: "Работает",
  FIRED: "Уволен",
  INCOMPLETE: "Не заполнена",
  REJECTED: "Отклонено",
};
const money = (x: any) =>
  new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 2 }).format(
    Number(x || 0),
  );
const today = () => new Date().toLocaleDateString("en-CA");
function Status({ value }: { value: string }) {
  return (
    <Tag
      color={
        {
          POSTED: "green",
          APPROVED: "cyan",
          PENDING: "gold",
          CANCELLED: "default",
          ACTIVE: "green",
          INCOMPLETE: "orange",
          FIRED: "default",
        }[value]
      }
    >
      {states[value] || value}
    </Tag>
  );
}
function ErrorBox({ error }: { error: unknown }) {
  return error ? (
    <Alert type="error" showIcon message={(error as Error).message} />
  ) : null;
}
function DataTable({ rows }: { rows: RowData[] }) {
  const keys = [...new Set(rows.flatMap(Object.keys))].filter(
    (k) => !["org", "payload", "data"].includes(k),
  );
  return (
    <Table
      rowKey={(r) => String(r.id || JSON.stringify(r))}
      size="small"
      scroll={{ x: "max-content" }}
      pagination={{ pageSize: 15 }}
      dataSource={rows}
      columns={keys.map((k) => ({
        title: k,
        dataIndex: k,
        render: (v: any) =>
          typeof v === "object" ? JSON.stringify(v) : String(v ?? "—"),
      }))}
    />
  );
}
export default function App() {
  const [ready, setReady] = useState(false),
    [loginError, setLoginError] = useState("");
  const [view, setView] = useState("home"),
    [org, setOrg] = useState("demo");
  const qc = useQueryClient();
  const { message } = AntApp.useApp();
  const me = useQuery({
    queryKey: ["me"],
    queryFn: () => api("/me"),
    enabled: ready,
  });
  useEffect(() => {
    if (!manager) return;
    async function init() {
      try {
        if (location.pathname === "/callback") {
          await manager!.signinRedirectCallback();
          history.replaceState({}, "", "/");
        }
        const user = await manager!.getUser();
        if (user && !user.expired) setReady(true);
      } catch (e) {
        setLoginError((e as Error).message);
      }
    }
    void init();
  }, []);
  useEffect(() => {
    if (
      me.data?.organizations.length &&
      !me.data.organizations.some((x: RowData) => x.id === org)
    )
      setOrg(me.data.organizations[0].id);
  }, [me.data, org]);
  const roles: string[] = me.data?.roles || [];
  const can = (...r: string[]) =>
    roles.includes("admin") || r.some((x) => roles.includes(x));
  const base = "/orgs/" + org;
  async function run(task: () => Promise<unknown>) {
    try {
      await task();
      await qc.invalidateQueries();
      message.success("Готово");
      return true;
    } catch (e) {
      message.error((e as Error).message);
      return false;
    }
  }
  if (!ready || me.error)
    return (
      <div className="login">
        <div className="login-story">
          <div className="wordmark">
            wax<span>ERP</span>
          </div>
          <Title>
            Процессы.
            <br />
            Автоматизация.
            <br />
            Исполнение.
          </Title>
          <Paragraph>
            Закупки, продажи, склад и команда —<br />в одном рабочем
            пространстве.
          </Paragraph>
          <div className="login-line" />
          <Text>Workflows. Automation. eXecution.</Text>
        </div>
        <Card className="login-card">
          <Title level={3}>Добро пожаловать</Title>
          <Paragraph type="secondary">
            {isDemo
              ? "Локальная демонстрация. Используйте пароль, заданный при запуске сервера."
              : "Войдите через корпоративную учётную запись. При первом входе настройте второй фактор."}
          </Paragraph>
          {isDemo ? (
            <Form
              layout="vertical"
              onFinish={async (v) => {
                demoLogin(v.user, v.password);
                try {
                  await api("/me");
                  setReady(true);
                  setLoginError("");
                  await qc.invalidateQueries();
                } catch (e) {
                  setLoginError((e as Error).message);
                }
              }}
            >
              <Form.Item name="user" label="Пользователь" initialValue="admin">
                <Select
                  options={[
                    "admin",
                    "manager",
                    "purchasing",
                    "sales",
                    "warehouse",
                    "hr",
                    "employee",
                  ].map((x) => ({ label: x, value: x }))}
                />
              </Form.Item>
              <Form.Item
                name="password"
                label="Пароль"
                rules={[{ required: true }]}
              >
                <Input.Password autoComplete="current-password" />
              </Form.Item>
              <Button type="primary" htmlType="submit" block size="large">
                Войти
              </Button>
            </Form>
          ) : (
            <Button
              type="primary"
              block
              size="large"
              onClick={() => manager?.signinRedirect()}
            >
              Войти через Keycloak
            </Button>
          )}
          {loginError && (
            <Alert
              type="error"
              message={loginError}
              style={{ marginTop: 16 }}
            />
          )}
          <ErrorBox error={me.error} />
          <div className="prototype-label">ПРОТОТИП · 0.1</div>
        </Card>
      </div>
    );
  if (me.isLoading)
    return (
      <div className="loading">
        <Spin size="large" />
      </div>
    );
  const nav = [
    { key: "home", icon: <AppstoreOutlined />, label: "Обзор" },
    { key: "documents", icon: <FileTextOutlined />, label: "Документы" },
    ...(can("warehouse", "sales", "purchasing", "manager")
      ? [{ key: "stock", icon: <InboxOutlined />, label: "Склад" }]
      : []),
    { key: "employees", icon: <TeamOutlined />, label: "Персонал" },
    { key: "absences", icon: <CalendarOutlined />, label: "Отсутствия" },
    ...(can("hr", "manager", "profit")
      ? [{ key: "reports", icon: <BarChartOutlined />, label: "Отчёты" }]
      : []),
    ...(can("mdm", "purchasing", "sales", "warehouse", "hr", "manager")
      ? [{ key: "masters", icon: <SettingOutlined />, label: "Справочники" }]
      : []),
    ...(can("admin")
      ? [
          { key: "audit", icon: <AuditOutlined />, label: "Аудит" },
          {
            key: "admin",
            icon: <SettingOutlined />,
            label: "Администрирование",
          },
        ]
      : []),
  ];
  return (
    <Layout className="shell">
      <Layout.Sider
        width={228}
        breakpoint="lg"
        collapsedWidth={72}
        className="sidebar"
      >
        <div className="brand">
          wax<span>WORKSPACE</span>
        </div>
        <Menu
          selectedKeys={[view]}
          onClick={(e) => setView(e.key)}
          items={nav}
        />
        <div className="sidebar-foot">
          Workflows.
          <br />
          Automation.
          <br />
          eXecution.
          <br />
          <Tag>Прототип 0.1</Tag>
        </div>
      </Layout.Sider>
      <Layout>
        <Layout.Header className="topbar">
          <Space>
            <span className="live-dot" />
            <Text>Рабочее пространство</Text>
            <Select
              aria-label="Юридическое лицо"
              value={org}
              onChange={(v) => {
                setOrg(v);
                qc.invalidateQueries();
              }}
              options={me.data.organizations.map((o: RowData) => ({
                value: o.id,
                label: o.name,
              }))}
              style={{ width: 280 }}
            />
          </Space>
          <Space>
            <Tag>{me.data.username}</Tag>
            <Button
              aria-label="Выйти"
              icon={<LogoutOutlined />}
              onClick={() => logout()}
            />
          </Space>
        </Layout.Header>
        <Layout.Content className="workspace">
          <div className="page-top">
            <div>
              <Text type="secondary">
                WAX / {nav.find((x) => x.key === view)?.label.toUpperCase()}
              </Text>
              <Title level={2}>{nav.find((x) => x.key === view)?.label}</Title>
            </div>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => qc.invalidateQueries()}
            >
              Обновить
            </Button>
          </div>
          {view === "home" && <Home base={base} navigate={setView} />}
          {view === "documents" && (
            <Documents base={base} run={run} can={can} />
          )}
          {view === "stock" && <Stock base={base} />}
          {view === "employees" && (
            <Employees base={base} run={run} can={can} />
          )}
          {view === "absences" && <Absences base={base} run={run} can={can} />}
          {view === "reports" && <Reports base={base} run={run} can={can} />}
          {view === "masters" && <Masters base={base} run={run} can={can} />}
          {view === "audit" && <RemoteTable path={base + "/audit"} />}
          {view === "admin" && <Admin base={base} run={run} />}
          <footer>
            wax · Прототип для проверки процессов · Реальные персональные
            данные пока не загружайте
          </footer>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
type Props = {
  base: string;
  run: (task: () => Promise<unknown>) => Promise<boolean>;
  can: (...r: string[]) => boolean;
};
function Home({
  base,
  navigate,
}: {
  base: string;
  navigate: (s: string) => void;
}) {
  const q = useQuery({
    queryKey: [base, "overview"],
    queryFn: () => api(base + "/documents?size=8"),
  });
  const pending = useQuery({
    queryKey: [base, "pending"],
    queryFn: () => api(base + "/documents?state=PENDING&size=5"),
  });
  return (
    <>
      <div className="hero">
        <div>
          <div className="eyebrow">Workflows. Automation. eXecution.</div>
          <Title>От заявки — к результату.</Title>
          <Paragraph>
            Документы сохраняют историю, склад — точные остатки,
            <br />а команда видит следующий шаг.
          </Paragraph>
          <Button
            type="primary"
            size="large"
            onClick={() => navigate("documents")}
          >
            Открыть документы <ArrowRightOutlined />
          </Button>
        </div>
        <div className="hero-symbol">
          wa<span>x</span>
        </div>
      </div>
      <Row gutter={20}>
        <Col xs={24} md={8}>
          <Card>
            <Statistic
              title="Всего доступных документов"
              value={q.data?.total ?? 0}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic
              title="Ожидают согласования"
              value={pending.data?.total ?? 0}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Text type="secondary">Рабочий контур</Text>
            <Title level={3} style={{ margin: "10px 0 0" }}>
              Закупки → Склад → Продажи
            </Title>
          </Card>
        </Col>
      </Row>
      <Card
        title="Последние документы"
        style={{ marginTop: 20 }}
        extra={
          <Button type="link" onClick={() => navigate("documents")}>
            Все документы
          </Button>
        }
      >
        <ErrorBox error={q.error} />
        <Table
          rowKey="id"
          pagination={false}
          dataSource={q.data?.items || []}
          columns={[
            { title: "Номер", dataIndex: "number" },
            { title: "Вид", dataIndex: "kind", render: (v) => kinds[v] },
            { title: "Дата", dataIndex: "doc_date" },
            {
              title: "Статус",
              dataIndex: "state",
              render: (v) => <Status value={v} />,
            },
            {
              title: "Сумма",
              render: (_, r: RowData) => money(r.total) + " " + r.currency,
            },
          ]}
        />
      </Card>
    </>
  );
}
function RemoteTable({ path }: { path: string }) {
  const q = useQuery({ queryKey: [path], queryFn: () => api(path) });
  return (
    <Card>
      <ErrorBox error={q.error} />
      {q.isLoading ? <Spin /> : <DataTable rows={q.data || []} />}
    </Card>
  );
}
function Documents({ base, run, can }: Props) {
  const [kind, setKind] = useState(""),
    [state, setState] = useState(""),
    [search, setSearch] = useState(""),
    [page, setPage] = useState(1),
    [selected, setSelected] = useState(""),
    [editing, setEditing] = useState<RowData | null>(null);
  const [form] = Form.useForm();
  const q = useQuery({
    queryKey: [base, "documents", kind, state, search, page],
    queryFn: () =>
      api(
        base +
          "/documents?" +
          new URLSearchParams({
            kind,
            state,
            search,
            page: String(page - 1),
            size: "20",
          }),
      ),
  });
  const detail = useQuery({
    queryKey: [base, "document", selected],
    queryFn: () => api(base + "/documents/" + selected),
    enabled: !!selected,
  });
  const d: RowData = detail.data || {};
  const items = useQuery({
    queryKey: [base, "items"],
    queryFn: () => api(base + "/masters?kind=item"),
  });
  const warehouses = useQuery({
    queryKey: [base, "warehouses"],
    queryFn: () => api(base + "/masters?kind=warehouse"),
  });
  const partners = useQuery({
    queryKey: [base, "partners"],
    queryFn: () => api(base + "/masters?kind=partner"),
  });
  const employees = useQuery({
    queryKey: [base, "employees"],
    queryFn: () => api(base + "/employees"),
  });
  function edit(p: RowData) {
    const x = {
      ...p,
      lines: (p.lines || []).map((l: RowData) => ({
        ...l,
        serialText: (l.serials || []).join("\n"),
      })),
    };
    setEditing(x);
    form.resetFields();
    form.setFieldsValue(x);
  }
  async function action(a: string) {
    const comment = a === "reject" ? prompt("Причина отказа") : "";
    if (a === "reject" && !comment) return;
    await run(() =>
      api(base + "/documents/" + selected + "/actions/" + a, "POST", {
        version: d.version,
        comment,
      }),
    );
  }
  async function related(k: string) {
    edit({
      ...d.payload,
      id: undefined,
      version: undefined,
      kind: k,
      source: d.id,
      date: today(),
      lines: d.payload.lines.map((l: RowData) => ({ ...l, serials: [] })),
    });
  }
  return (
    <>
      <Card>
        <div className="toolbar">
          <Space wrap>
            <Select
              aria-label="Вид документа"
              value={kind}
              style={{ width: 210 }}
              onChange={(v) => {
                setKind(v);
                setPage(1);
              }}
              options={[
                { value: "", label: "Все виды" },
                ...Object.entries(kinds).map(([value, label]) => ({
                  value,
                  label,
                })),
              ]}
            />
            <Select
              aria-label="Статус"
              value={state}
              style={{ width: 185 }}
              onChange={(v) => {
                setState(v);
                setPage(1);
              }}
              options={[
                { value: "", label: "Все состояния" },
                ...Object.entries(states)
                  .filter(([k]) =>
                    [
                      "DRAFT",
                      "PENDING",
                      "APPROVED",
                      "POSTED",
                      "CANCELLED",
                    ].includes(k),
                  )
                  .map(([value, label]) => ({ value, label })),
              ]}
            />
            <Input.Search
              placeholder="Поиск по номеру"
              onSearch={(v) => {
                setSearch(v);
                setPage(1);
              }}
              style={{ width: 210 }}
            />
          </Space>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() =>
              edit({
                kind: kind || "PO",
                date: today(),
                currency: "USD",
                warehouse: "wh-main",
                lines: [{ item: "server", qty: 1, price: 1000 }],
              })
            }
          >
            Создать документ
          </Button>
        </div>
        <ErrorBox error={q.error} />
        <Table
          rowKey="id"
          loading={q.isLoading}
          dataSource={q.data?.items || []}
          pagination={{
            current: page,
            total: q.data?.total,
            pageSize: 20,
            onChange: setPage,
            showSizeChanger: false,
          }}
          columns={[
            {
              title: "Номер",
              dataIndex: "number",
              render: (v, r: RowData) => (
                <Button type="link" onClick={() => setSelected(r.id)}>
                  {v}
                </Button>
              ),
            },
            { title: "Вид", dataIndex: "kind", render: (v) => kinds[v] },
            { title: "Дата", dataIndex: "doc_date" },
            {
              title: "Состояние",
              dataIndex: "state",
              render: (v) => <Status value={v} />,
            },
            { title: "Автор", dataIndex: "creator" },
            {
              title: "Сумма",
              render: (_, r: RowData) => money(r.total) + " " + r.currency,
            },
          ]}
        />
      </Card>
      <Modal
        open={!!selected}
        title={d.number || "Документ"}
        width={1080}
        onCancel={() => setSelected("")}
        footer={null}
        destroyOnHidden
      >
        <ErrorBox error={detail.error} />
        {detail.isLoading ? (
          <Spin />
        ) : (
          detail.data && (
            <>
              <Space wrap style={{ marginBottom: 20 }}>
                <Status value={d.state} />
                <Button
                  onClick={() =>
                    download(
                      base + "/documents/" + selected + "/pdf",
                      d.number + ".pdf",
                    ).catch((e) => Modal.error({ content: e.message }))
                  }
                >
                  PDF
                </Button>
                {d.state === "DRAFT" && (
                  <>
                    <Button
                      onClick={() =>
                        edit({ ...d.payload, id: d.id, version: d.version })
                      }
                    >
                      Редактировать
                    </Button>
                    <Button onClick={() => action("submit")}>
                      На согласование
                    </Button>
                  </>
                )}
                {d.state === "PENDING" && can("manager") && (
                  <>
                    <Button onClick={() => action("approve")}>
                      Согласовать
                    </Button>
                    <Button onClick={() => action("reject")}>Отклонить</Button>
                  </>
                )}
                {["DRAFT", "APPROVED"].includes(d.state) && (
                  <Button type="primary" onClick={() => action("post")}>
                    Провести
                  </Button>
                )}
                {d.state !== "CANCELLED" && (
                  <Popconfirm
                    title="Отменить документ?"
                    onConfirm={() => action("cancel")}
                  >
                    <Button danger>Отменить</Button>
                  </Popconfirm>
                )}
              </Space>
              <Descriptions
                column={3}
                bordered
                size="small"
                items={[
                  { key: "type", label: "Вид", children: kinds[d.kind] },
                  { key: "date", label: "Дата", children: d.doc_date },
                  {
                    key: "total",
                    label: "Итого",
                    children: money(d.total) + " " + d.currency,
                  },
                  { key: "net", label: "Без НДС", children: money(d.net) },
                  {
                    key: "rate",
                    label: "Курс к рублю",
                    children: money(d.rate),
                  },
                  { key: "version", label: "Версия", children: d.version },
                ]}
              />
              <Tabs
                items={[
                  {
                    key: "lines",
                    label: "Позиции",
                    children: (
                      <Table
                        rowKey="item"
                        pagination={false}
                        dataSource={d.payload.lines}
                        columns={[
                          {
                            title: "Номенклатура",
                            dataIndex: "item",
                            render: (v) =>
                              items.data?.find((i: RowData) => i.id === v)
                                ?.name || v,
                          },
                          { title: "Количество", dataIndex: "qty" },
                          { title: "Цена", dataIndex: "price", render: money },
                          { title: "НДС, %", dataIndex: "vat" },
                          { title: "Сумма", dataIndex: "total", render: money },
                          {
                            title: "Серийные номера",
                            dataIndex: "serials",
                            render: (v) => (v || []).join(", "),
                          },
                        ]}
                      />
                    ),
                  },
                  {
                    key: "chain",
                    label: "Цепочка документов",
                    children: (
                      <>
                        <Space wrap>
                          {d.source_id && (
                            <Button onClick={() => setSelected(d.source_id)}>
                              Открыть основание
                            </Button>
                          )}
                          {d.state === "POSTED" &&
                            (d.kind === "PO"
                              ? ["RECEIPT"]
                              : d.kind === "SO"
                                ? ["INVOICE", "SHIPMENT"]
                                : d.kind === "RECEIPT"
                                  ? ["RETURN_OUT"]
                                  : d.kind === "SHIPMENT"
                                    ? ["RETURN_IN"]
                                    : []
                            ).map((k) => (
                              <Button key={k} onClick={() => related(k)}>
                                Создать: {kinds[k]}
                              </Button>
                            ))}
                          {d.kind === "SO" && d.state === "POSTED" && (
                            <>
                              <Button
                                onClick={() =>
                                  run(() =>
                                    api(
                                      base +
                                        "/documents/" +
                                        selected +
                                        "/reserve",
                                      "POST",
                                      {},
                                    ),
                                  )
                                }
                              >
                                Зарезервировать
                              </Button>
                              <Button
                                onClick={() =>
                                  run(() =>
                                    api(
                                      base +
                                        "/documents/" +
                                        selected +
                                        "/reserve",
                                      "DELETE",
                                    ),
                                  )
                                }
                              >
                                Снять резерв
                              </Button>
                            </>
                          )}
                        </Space>
                        <Table
                          rowKey="id"
                          pagination={false}
                          dataSource={d.children}
                          columns={[
                            {
                              title: "Номер",
                              dataIndex: "number",
                              render: (v, r: RowData) => (
                                <Button
                                  type="link"
                                  onClick={() => setSelected(r.id)}
                                >
                                  {v}
                                </Button>
                              ),
                            },
                            {
                              title: "Состояние",
                              dataIndex: "state",
                              render: (v) => <Status value={v} />,
                            },
                            {
                              title: "Итого",
                              dataIndex: "total",
                              render: money,
                            },
                          ]}
                        />
                      </>
                    ),
                  },
                  {
                    key: "payments",
                    label: "Оплаты",
                    children: (
                      <>
                        <DataTable rows={d.payments || []} />
                        {["PO", "INVOICE"].includes(d.kind) &&
                          d.state === "POSTED" && (
                            <Form
                              layout="inline"
                              onFinish={(v) =>
                                run(() =>
                                  api(
                                    base +
                                      "/documents/" +
                                      selected +
                                      "/payments",
                                    "POST",
                                    v,
                                    crypto.randomUUID(),
                                  ),
                                )
                              }
                            >
                              <Form.Item
                                name="date"
                                initialValue={today()}
                                rules={[{ required: true }]}
                              >
                                <Input type="date" />
                              </Form.Item>
                              <Form.Item
                                name="amount"
                                rules={[{ required: true }]}
                              >
                                <InputNumber
                                  min={0.01}
                                  placeholder="Сумма"
                                  stringMode
                                />
                              </Form.Item>
                              <Button htmlType="submit" type="primary">
                                Записать оплату
                              </Button>
                            </Form>
                          )}
                      </>
                    ),
                  },
                  {
                    key: "files",
                    label: "Файлы и комментарии",
                    children: (
                      <>
                        <Upload
                          showUploadList={false}
                          beforeUpload={(file) => {
                            const data = new FormData();
                            data.append("file", file);
                            run(() =>
                              request(
                                base + "/documents/" + selected + "/files",
                                { method: "POST", body: data },
                              ),
                            );
                            return false;
                          }}
                        >
                          <Button>Прикрепить файл (до 10 МБ)</Button>
                        </Upload>
                        <Space wrap style={{ margin: 12 }}>
                          {d.attachments?.map((f: RowData) => (
                            <Button
                              key={f.id}
                              onClick={() =>
                                download(base + "/files/" + f.id, f.name)
                              }
                            >
                              {f.name}
                            </Button>
                          ))}
                        </Space>
                        {d.comments?.map((c: RowData) => (
                          <Card
                            key={c.id}
                            size="small"
                            style={{ margin: "8px 0" }}
                          >
                            <Text strong>{c.author} </Text>
                            <Text type="secondary">{c.created_at}</Text>
                            <Paragraph>{c.body}</Paragraph>
                          </Card>
                        ))}
                        <Form
                          onFinish={(v) =>
                            run(() =>
                              api(
                                base + "/documents/" + selected + "/comments",
                                "POST",
                                v,
                              ),
                            )
                          }
                        >
                          <Form.Item name="text" rules={[{ required: true }]}>
                            <Input.TextArea placeholder="Комментарий" />
                          </Form.Item>
                          <Button htmlType="submit">
                            Добавить комментарий
                          </Button>
                        </Form>
                      </>
                    ),
                  },
                ]}
              />
            </>
          )
        )}
      </Modal>
      <Modal
        open={!!editing}
        title={editing?.id ? "Редактирование документа" : "Новый документ"}
        width={1060}
        onCancel={() => setEditing(null)}
        onOk={() => form.submit()}
        okText="Сохранить черновик"
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={async (v) => {
            const p = {
              ...editing,
              ...v,
              lines: v.lines.map((l: RowData) => ({
                ...l,
                serials: (l.serialText || "")
                  .split(/[\n,]+/)
                  .map((s: string) => s.trim())
                  .filter(Boolean),
              })),
            };
            if (
              await run(async () => {
                const saved = await api(
                  base + "/documents",
                  "POST",
                  p,
                  crypto.randomUUID(),
                );
                setSelected(saved.id);
              })
            )
              setEditing(null);
          }}
        >
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="kind"
                label="Вид документа"
                rules={[{ required: true }]}
              >
                <Select
                  disabled={!!editing?.id}
                  options={Object.entries(kinds).map(([value, label]) => ({
                    value,
                    label,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="date" label="Дата" rules={[{ required: true }]}>
                <Input type="date" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="currency" label="Валюта">
                <Select
                  options={["RUB", "USD", "EUR"].map((value) => ({
                    value,
                    label: value,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="warehouse" label="Склад">
                <Select
                  options={warehouses.data
                    ?.filter((x: RowData) => x.active)
                    .map((x: RowData) => ({ value: x.id, label: x.name }))}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="partner" label="Контрагент">
                <Select
                  allowClear
                  options={partners.data
                    ?.filter((x: RowData) => x.active)
                    .map((x: RowData) => ({ value: x.id, label: x.name }))}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="targetWarehouse"
                label="Склад-получатель (перемещение)"
              >
                <Select
                  allowClear
                  options={warehouses.data?.map((x: RowData) => ({
                    value: x.id,
                    label: x.name,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="employee" label="Сотрудник (выдача / возврат)">
                <Select
                  allowClear
                  options={employees.data?.map((x: RowData) => ({
                    value: x.id,
                    label: x.name,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="source" label="ID документа-основания">
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.List name="lines">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...rest }) => (
                  <div className="line-editor" key={key}>
                    <Form.Item
                      {...rest}
                      name={[name, "item"]}
                      label="Номенклатура"
                      rules={[{ required: true }]}
                    >
                      <Select
                        options={items.data
                          ?.filter((x: RowData) => x.active)
                          .map((x: RowData) => ({
                            value: x.id,
                            label: x.name,
                          }))}
                      />
                    </Form.Item>
                    <Form.Item
                      {...rest}
                      name={[name, "qty"]}
                      label="Кол-во"
                      rules={[{ required: true }]}
                    >
                      <InputNumber stringMode min={0.001} />
                    </Form.Item>
                    <Form.Item
                      {...rest}
                      name={[name, "price"]}
                      label="Цена без НДС"
                      rules={[{ required: true }]}
                    >
                      <InputNumber stringMode min={0} />
                    </Form.Item>
                    <Form.Item
                      {...rest}
                      name={[name, "discount"]}
                      label="Скидка, %"
                    >
                      <InputNumber stringMode min={0} max={100} />
                    </Form.Item>
                    <Form.Item
                      {...rest}
                      name={[name, "serialText"]}
                      label="Серийные номера"
                    >
                      <Input.TextArea
                        rows={2}
                        placeholder="По одному в строке"
                      />
                    </Form.Item>
                    <Button danger onClick={() => remove(name)}>
                      ×
                    </Button>
                  </div>
                ))}
                <Button
                  type="dashed"
                  onClick={() => add({ qty: 1, price: 0 })}
                  block
                >
                  Добавить позицию
                </Button>
              </>
            )}
          </Form.List>
          <Form.Item
            name="reason"
            label="Обоснование / условия / причина"
            style={{ marginTop: 16 }}
          >
            <Input.TextArea rows={2} />
          </Form.Item>
          <Alert
            type="info"
            message="Курс и НДС рассчитывает сервер. Для поступления и реализации выберите проведённое основание; для серийного товара заполните номера каждой единицы."
          />
        </Form>
      </Modal>
    </>
  );
}
function Stock({ base }: { base: string }) {
  const [date, setDate] = useState("");
  const q = useQuery({
    queryKey: [base, "stock", date],
    queryFn: () => api(base + "/stock?date=" + date),
  });
  return (
    <Card
      title="Остатки и стоимость"
      extra={
        <Space>
          <Input
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
          <Button onClick={() => setDate("")}>Сейчас</Button>
          <Button
            icon={<DownloadOutlined />}
            onClick={() =>
              download(
                base + "/export/stock?format=xlsx&date=" + date,
                "stock.xlsx",
              )
            }
          >
            Excel
          </Button>
        </Space>
      }
    >
      <ErrorBox error={q.error} />
      <DataTable rows={q.data || []} />
    </Card>
  );
}
function Employees({ base, run, can }: Props) {
  const q = useQuery({
    queryKey: [base, "employees"],
    queryFn: () => api(base + "/employees"),
  });
  const [editing, setEditing] = useState<RowData | null>(null),
    [selected, setSelected] = useState(""),
    [form] = Form.useForm();
  const detail = useQuery({
    queryKey: [base, "employee", selected],
    queryFn: () => api(base + "/employees/" + selected),
    enabled: !!selected,
  });
  function edit(x: RowData) {
    setEditing(x);
    form.resetFields();
    form.setFieldsValue({ ...x, ...x.data });
  }
  async function save(v: RowData) {
    const { name, code, username, ...data } = v;
    const p = {
      id: editing?.id,
      version: editing?.version,
      name,
      code,
      username,
      data,
    };
    if (await run(() => api(base + "/employees", "POST", p))) setEditing(null);
  }
  async function event(kind: string) {
    const reason = prompt("Основание кадрового события");
    if (!reason) return;
    await run(() =>
      api(base + "/employees/" + selected + "/events", "POST", {
        kind,
        date: today(),
        reason,
      }),
    );
  }
  return (
    <>
      <Card
        title="Сотрудники"
        extra={
          <Space>
            <Button
              onClick={() =>
                download(
                  base + "/export/employees?format=xlsx",
                  "employees.xlsx",
                )
              }
            >
              Excel
            </Button>
            {can("hr") && (
              <>
                <Upload
                  showUploadList={false}
                  beforeUpload={(file) => {
                    run(async () => {
                      const result = await request(base + "/employees/import", {
                        method: "POST",
                        headers: { "Content-Type": "text/plain" },
                        body: await file.text(),
                      });
                      const rows = await result.json();
                      Modal.info({
                        title: "Результат импорта",
                        width: 900,
                        content: <DataTable rows={rows} />,
                      });
                    });
                    return false;
                  }}
                >
                  <Button>Импорт CSV</Button>
                </Upload>
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => edit({ data: {} })}
                >
                  Новый сотрудник
                </Button>
              </>
            )}
          </Space>
        }
      >
        <ErrorBox error={q.error} />
        <Table
          rowKey="id"
          loading={q.isLoading}
          dataSource={q.data || []}
          columns={[
            { title: "Табельный код", dataIndex: "code" },
            {
              title: "ФИО",
              dataIndex: "name",
              render: (v, r: RowData) => (
                <Button type="link" onClick={() => setSelected(r.id)}>
                  {v}
                </Button>
              ),
            },
            {
              title: "Состояние",
              dataIndex: "state",
              render: (v) => <Status value={v} />,
            },
            { title: "Приём", dataIndex: "hired_on" },
            { title: "Увольнение", dataIndex: "fired_on" },
            { title: "Учётная запись", dataIndex: "username" },
          ]}
        />
      </Card>
      <Modal
        open={!!selected}
        width={900}
        title={detail.data?.name || "Сотрудник"}
        onCancel={() => setSelected("")}
        footer={null}
      >
        <ErrorBox error={detail.error} />
        {detail.data && (
          <>
            <Space style={{ marginBottom: 16 }}>
              <Status value={detail.data.state} />
              {can("hr") && (
                <>
                  <Button onClick={() => edit(detail.data)}>
                    Редактировать
                  </Button>
                  {detail.data.state === "INCOMPLETE" && (
                    <Button type="primary" onClick={() => event("HIRE")}>
                      Оформить приём
                    </Button>
                  )}
                  {detail.data.state === "ACTIVE" && (
                    <Popconfirm
                      title="Оформить увольнение сегодня?"
                      onConfirm={() => event("FIRE")}
                    >
                      <Button danger>Уволить</Button>
                    </Popconfirm>
                  )}
                </>
              )}
            </Space>
            <Tabs
              items={[
                {
                  key: "data",
                  label: "Карточка",
                  children: (
                    <Descriptions
                      bordered
                      column={2}
                      items={Object.entries(detail.data.data).map(
                        ([key, v]) => ({
                          key,
                          label: key,
                          children: String(v),
                        }),
                      )}
                    />
                  ),
                },
                {
                  key: "equipment",
                  label: "Оборудование / обходной лист",
                  children: <DataTable rows={detail.data.equipment || []} />,
                },
                {
                  key: "events",
                  label: "Кадровые события",
                  children: <DataTable rows={detail.data.events || []} />,
                },
                {
                  key: "absences",
                  label: "Отсутствия",
                  children: <DataTable rows={detail.data.absences || []} />,
                },
              ]}
            />
          </>
        )}
      </Modal>
      <Modal
        open={!!editing}
        title="Карточка сотрудника"
        width={850}
        onCancel={() => setEditing(null)}
        onOk={() => form.submit()}
        okText="Сохранить"
      >
        <Form layout="vertical" form={form} onFinish={save}>
          <Row gutter={16}>
            {[
              ["name", "Полное ФИО"],
              ["code", "Табельный код"],
              ["firstName", "Имя"],
              ["lastName", "Фамилия"],
              ["middleName", "Отчество"],
              ["birthDate", "Дата рождения"],
              ["phone", "Телефон"],
              ["address", "Адрес"],
              ["passport", "Паспорт"],
              ["department", "ID подразделения"],
              ["position", "ID должности"],
              ["manager", "Логин руководителя"],
              ["username", "Логин в Keycloak"],
            ].map(([k, label]) => (
              <Col span={12} key={k}>
                <Form.Item
                  name={k}
                  label={label}
                  rules={
                    ["name", "code"].includes(k) ? [{ required: true }] : []
                  }
                >
                  <Input type={k === "birthDate" ? "date" : "text"} />
                </Form.Item>
              </Col>
            ))}
          </Row>
          <Form.Item
            name="noMiddleName"
            label="Отчество отсутствует"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Alert
            type="info"
            message="Неполную карточку можно сохранить, но оформить приём — только после заполнения обязательных сведений. Учётную запись в Keycloak создаёт администратор отдельно."
          />
        </Form>
      </Modal>
    </>
  );
}
function Absences({ base, run, can }: Props) {
  const q = useQuery({
    queryKey: [base, "absences"],
    queryFn: () => api(base + "/absences"),
  });
  const employees = useQuery({
    queryKey: [base, "employees"],
    queryFn: () => api(base + "/employees"),
  });
  const [open, setOpen] = useState(false),
    [form] = Form.useForm();
  return (
    <>
      <Card
        title="Заявки на отсутствие"
        extra={
          <Button type="primary" onClick={() => setOpen(true)}>
            Новая заявка
          </Button>
        }
      >
        <ErrorBox error={q.error} />
        <Table
          rowKey="id"
          dataSource={q.data || []}
          columns={[
            { title: "Сотрудник", dataIndex: "name" },
            { title: "Вид", dataIndex: "kind" },
            { title: "С", dataIndex: "starts" },
            { title: "По", dataIndex: "ends" },
            {
              title: "Состояние",
              dataIndex: "state",
              render: (v) => <Status value={v} />,
            },
            {
              title: "Решение",
              render: (_, r: RowData) =>
                r.state === "PENDING" && can("manager") ? (
                  <Space>
                    <Button
                      onClick={() =>
                        run(() =>
                          api(
                            base + "/absences/" + r.id + "/decision",
                            "POST",
                            { approve: true },
                          ),
                        )
                      }
                    >
                      Согласовать
                    </Button>
                    <Button
                      onClick={() => {
                        const comment = prompt("Причина отказа");
                        if (comment)
                          run(() =>
                            api(
                              base + "/absences/" + r.id + "/decision",
                              "POST",
                              { approve: false, comment },
                            ),
                          );
                      }}
                    >
                      Отклонить
                    </Button>
                  </Space>
                ) : (
                  r.comment
                ),
            },
          ]}
        />
      </Card>
      <Modal
        title="Заявка на отсутствие"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        okText="Отправить"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={async (v) => {
            if (await run(() => api(base + "/absences", "POST", v))) {
              form.resetFields();
              setOpen(false);
            }
          }}
        >
          <Form.Item
            name="employee"
            label="Сотрудник"
            rules={[{ required: true }]}
          >
            <Select
              options={employees.data
                ?.filter((e: RowData) => e.state === "ACTIVE")
                .map((e: RowData) => ({ value: e.id, label: e.name }))}
            />
          </Form.Item>
          <Form.Item name="kind" label="Вид" initialValue="VACATION">
            <Select
              options={[
                { value: "VACATION", label: "Отпуск" },
                { value: "UNPAID", label: "Без оплаты" },
                { value: "TRIP", label: "Командировка" },
                { value: "OTHER", label: "Другое" },
                ...(can("hr") ? [{ value: "SICK", label: "Больничный" }] : []),
              ]}
            />
          </Form.Item>
          <Form.Item name="from" label="С" rules={[{ required: true }]}>
            <Input type="date" />
          </Form.Item>
          <Form.Item name="to" label="По" rules={[{ required: true }]}>
            <Input type="date" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
function Reports({ base, run, can }: Props) {
  const [tab, setTab] = useState("employees"),
    [month, setMonth] = useState(today().slice(0, 7)),
    [sheet, setSheet] = useState<RowData | null>(null);
  return (
    <Card>
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          {
            key: "employees",
            label: "Список сотрудников",
            children: (
              <>
                <Space>
                  <Button
                    onClick={() =>
                      download(
                        base + "/export/employees?format=xlsx",
                        "employees.xlsx",
                      )
                    }
                  >
                    Excel
                  </Button>
                  <Button
                    onClick={() =>
                      download(base + "/export/employees", "employees.csv")
                    }
                  >
                    CSV
                  </Button>
                </Space>
                <RemoteTable path={base + "/employees"} />
              </>
            ),
          },
          ...(can("profit")
            ? [
                {
                  key: "profit",
                  label: "Валовая прибыль",
                  children: (
                    <>
                      <Button
                        onClick={() =>
                          download(
                            base + "/export/profit?format=xlsx",
                            "profit.xlsx",
                          )
                        }
                      >
                        Excel
                      </Button>
                      <RemoteTable path={base + "/profit"} />
                    </>
                  ),
                },
              ]
            : []),
          ...(can("hr")
            ? [
                {
                  key: "timesheet",
                  label: "Табель и пакет 1С",
                  children: (
                    <>
                      <Space>
                        <Input
                          type="month"
                          value={month}
                          onChange={(e) => setMonth(e.target.value)}
                        />
                        <Button
                          onClick={() =>
                            run(async () =>
                              setSheet(
                                await api(
                                  base + "/timesheets/" + month,
                                  "POST",
                                  { close: false },
                                ),
                              ),
                            )
                          }
                        >
                          Сформировать
                        </Button>
                        <Popconfirm
                          title="Закрыть табель? Проверьте календарь: по умолчанию только выходные суббота/воскресенье."
                          onConfirm={() =>
                            run(async () =>
                              setSheet(
                                await api(
                                  base + "/timesheets/" + month,
                                  "POST",
                                  { close: true },
                                ),
                              ),
                            )
                          }
                        >
                          <Button type="primary">
                            Закрыть и создать пакет 1С
                          </Button>
                        </Popconfirm>
                      </Space>
                      {sheet && (
                        <>
                          <Alert
                            style={{ margin: "16px 0" }}
                            type="info"
                            message={
                              sheet.closed
                                ? "Табель закрыт. Пакет доступен в очереди обмена."
                                : "Предварительный табель — проверьте производственный календарь."
                            }
                          />
                          <DataTable rows={sheet.data?.days || []} />
                        </>
                      )}
                    </>
                  ),
                },
              ]
            : []),
          {
            key: "outbox",
            label: "События / очередь 1С",
            children: <Outbox base={base} />,
          },
        ]}
      />
    </Card>
  );
}
function Outbox({ base }: { base: string }) {
  const q = useQuery({
    queryKey: [base, "outbox"],
    queryFn: () => api(base + "/outbox"),
  });
  return (
    <>
      <ErrorBox error={q.error} />
      <Table
        rowKey="id"
        dataSource={q.data || []}
        columns={[
          { title: "Событие", dataIndex: "topic" },
          { title: "Дата", dataIndex: "created_at" },
          {
            title: "Данные",
            render: (_, r: RowData) => (
              <Button
                onClick={() => {
                  const url = URL.createObjectURL(
                    new Blob([JSON.stringify(r.payload, null, 2)], {
                      type: "application/json",
                    }),
                  );
                  const a = document.createElement("a");
                  a.href = url;
                  a.download = r.topic + "-" + r.id + ".json";
                  a.click();
                  setTimeout(() => URL.revokeObjectURL(url), 1000);
                }}
              >
                Скачать JSON
              </Button>
            ),
          },
        ]}
      />
    </>
  );
}
const masterNames: Record<string, string> = {
  item: "Номенклатура",
  partner: "Контрагенты",
  warehouse: "Склады",
  department: "Подразделения",
  position: "Должности",
  contract: "Договоры",
  rate: "Курсы валют",
  currency: "Валюты",
  unit: "Единицы измерения",
  vat: "Ставки НДС",
  group: "Группы",
  calendar: "Производственный календарь",
  pricelist: "Прайс-листы",
  settings: "Политика организации",
};
function Masters({ base, run, can }: Props) {
  const [kind, setKind] = useState("item"),
    [editing, setEditing] = useState<RowData | null>(null),
    [form] = Form.useForm();
  const q = useQuery({
    queryKey: [base, "masters", kind],
    queryFn: () => api(base + "/masters?kind=" + kind),
  });
  function edit(x: RowData) {
    setEditing(x);
    form.resetFields();
    form.setFieldsValue({ ...x, ...x.data });
  }
  const fields: Record<string, string[]> = {
    item: ["type", "unit", "vat", "warrantyMonths", "externalId", "group"],
    partner: ["type", "inn", "kpp", "address", "bank", "externalId"],
    warehouse: ["responsible"],
    department: ["parent", "manager"],
    contract: ["partner", "number", "date", "expires"],
    rate: ["currency", "date", "value"],
    currency: ["code"],
    unit: ["code"],
    vat: ["value", "date"],
    group: ["parent"],
    calendar: ["date"],
    pricelist: ["item", "price", "currency", "date", "expires"],
    settings: ["approvalUSD", "vacationDays"],
  };
  const labels: Record<string, string> = {
    type: "Вид",
    unit: "Единица измерения",
    vat: "НДС, %",
    warrantyMonths: "Гарантия, месяцев",
    externalId: "Внешний ID",
    inn: "ИНН",
    kpp: "КПП",
    address: "Адрес",
    bank: "Банковские реквизиты",
    responsible: "Ответственный",
    parent: "Родительский ID",
    manager: "Логин руководителя",
    partner: "ID контрагента",
    number: "Номер",
    date: "Дата",
    expires: "Окончание",
    currency: "Валюта",
    value: "Значение / курс",
    code: "Код",
    group: "ID группы",
    item: "ID номенклатуры",
    price: "Цена",
    approvalUSD: "Порог согласования, USD",
    vacationDays: "Отпуск, дней в год",
  };
  return (
    <>
      <Card
        extra={
          <Space>
            <Select
              style={{ width: 260 }}
              value={kind}
              onChange={setKind}
              options={Object.entries(masterNames).map(([value, label]) => ({
                value,
                label,
              }))}
            />
            {can("mdm") && (
              <Button
                type="primary"
                onClick={() => edit({ kind, active: true, data: {} })}
              >
                Добавить
              </Button>
            )}
          </Space>
        }
        title={masterNames[kind]}
      >
        <ErrorBox error={q.error} />
        <Table
          rowKey="id"
          dataSource={q.data || []}
          columns={[
            {
              title: "Наименование",
              dataIndex: "name",
              render: (v, r: RowData) => (
                <Button
                  type="link"
                  disabled={!can("mdm")}
                  onClick={() => edit(r)}
                >
                  {v}
                </Button>
              ),
            },
            { title: "ID для ссылок", dataIndex: "id" },
            {
              title: "Активен",
              dataIndex: "active",
              render: (v) => (v ? "Да" : "Нет"),
            },
            {
              title: "Сведения",
              dataIndex: "data",
              render: (v) => (
                <Text type="secondary">
                  {Object.entries(v || {})
                    .map(([k, x]) => (labels[k] || k) + ": " + x)
                    .join(" · ")}
                </Text>
              ),
            },
          ]}
        />
      </Card>
      <Modal
        title={masterNames[kind]}
        open={!!editing}
        onCancel={() => setEditing(null)}
        onOk={() => form.submit()}
        okText="Сохранить"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={async (v) => {
            const { name, active, ...data } = v;
            if (
              await run(() =>
                api(base + "/masters", "POST", {
                  id: editing?.id,
                  version: editing?.version,
                  kind,
                  name,
                  active,
                  data,
                }),
              )
            )
              setEditing(null);
          }}
        >
          <Form.Item
            label="Наименование"
            name="name"
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
          {(fields[kind] || []).map((k) => (
            <Form.Item key={k} name={k} label={labels[k] || k}>
              {k === "type" ? (
                <Select
                  options={(kind === "item"
                    ? ["GOODS", "SERVICE", "LICENSE"]
                    : ["SUPPLIER", "CUSTOMER"]
                  ).map((value) => ({ value, label: value }))}
                />
              ) : (
                <Input
                  type={["date", "expires"].includes(k) ? "date" : "text"}
                />
              )}
            </Form.Item>
          ))}
          {kind === "item" && (
            <Form.Item
              name="serial"
              label="Серийный учёт"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          )}
          {kind === "warehouse" && (
            <Form.Item
              name="locked"
              label="Заблокировать движения"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          )}
          {kind === "calendar" && (
            <Form.Item
              name="working"
              label="Рабочий день"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          )}
          <Form.Item
            name="active"
            label="Активная запись"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
function Admin({ base, run }: Omit<Props, "can">) {
  return (
    <>
      <Card title="Доступ пользователей">
        <Alert
          type="info"
          showIcon
          message="Сначала создайте пользователя в Keycloak и настройте MFA. Здесь задаются бизнес-роли и доступные юридические лица."
          style={{ marginBottom: 20 }}
        />
        <Form
          layout="inline"
          onFinish={(v) => run(() => api("/grants", "POST", v))}
        >
          <Form.Item name="username" rules={[{ required: true }]}>
            <Input placeholder="Логин Keycloak" />
          </Form.Item>
          <Form.Item name="roles" rules={[{ required: true }]}>
            <Input placeholder="sales,profit" />
          </Form.Item>
          <Form.Item
            name="orgs"
            initialValue={base.split("/")[2]}
            rules={[{ required: true }]}
          >
            <Input placeholder="demo" />
          </Form.Item>
          <Form.Item name="enabled" initialValue={true} valuePropName="checked">
            <Switch
              checkedChildren="Активен"
              unCheckedChildren="Заблокирован"
            />
          </Form.Item>
          <Button type="primary" htmlType="submit">
            Сохранить права
          </Button>
        </Form>
        <RemoteTable path="/grants" />
      </Card>
      <Card title="Закрытие учётного периода" style={{ marginTop: 20 }}>
        <Form
          layout="inline"
          onFinish={(v) => run(() => api(base + "/period", "POST", v))}
        >
          <Form.Item name="until" rules={[{ required: true }]}>
            <Input type="date" />
          </Form.Item>
          <Button htmlType="submit">Закрыть по выбранную дату</Button>
          <Popconfirm
            title="Открыть период?"
            onConfirm={() => run(() => api(base + "/period", "DELETE"))}
          >
            <Button danger>Открыть период</Button>
          </Popconfirm>
        </Form>
      </Card>
    </>
  );
}
