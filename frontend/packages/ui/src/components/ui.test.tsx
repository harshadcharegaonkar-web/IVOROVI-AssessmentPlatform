import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { applyBrandColor } from "../lib/branding";
import { Button } from "./Button";
import { DataTable, type Column } from "./DataTable";
import { Field, Input } from "./Field";
import { Switch } from "./Switch";

interface Row { id: string; name: string }
const columns: Column<Row>[] = [{ key: "name", header: "Name", cell: (r) => r.name, sortable: true }];

describe("DataTable", () => {
  it("renders rows and announces sort state", async () => {
    const onSort = vi.fn();
    render(<DataTable caption="People" columns={columns} rows={[{ id: "1", name: "Asha" }]} rowKey={(r) => r.id}
      sort={{ key: "name", direction: "asc" }} onSortChange={onSort} />);
    expect(screen.getByRole("table", { name: "People" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: /name/i })).toHaveAttribute("aria-sort", "ascending");
    await userEvent.click(screen.getByRole("button", { name: /name/i }));
    expect(onSort).toHaveBeenCalledWith({ key: "name", direction: "desc" });
  });

  it("shows empty state", () => {
    render(<DataTable caption="People" columns={columns} rows={[]} rowKey={(r) => r.id} empty={{ title: "Nobody here" }} />);
    expect(screen.getByText("Nobody here")).toBeInTheDocument();
  });

  it("shows error state with retry and reference", async () => {
    const retry = vi.fn();
    render(<DataTable caption="People" columns={columns} rows={undefined} rowKey={(r) => r.id}
      error={{ message: "Server unavailable", traceId: "abc123" }} onRetry={retry} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Server unavailable");
    expect(screen.getByText("abc123")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(retry).toHaveBeenCalled();
  });

  it("paginates", async () => {
    const onPage = vi.fn();
    render(<DataTable caption="People" columns={columns} rows={[{ id: "1", name: "A" }]} rowKey={(r) => r.id}
      page={{ page: 0, totalPages: 3, totalElements: 41, onPageChange: onPage }} />);
    expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next page" }));
    expect(onPage).toHaveBeenCalledWith(1);
  });
});

describe("Field", () => {
  it("associates label, hint and error with the control", () => {
    render(<Field label="Email" hint="Work address" error="Required">{(p) => <Input {...p} />}</Field>);
    const input = screen.getByLabelText("Email");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("Work address Required");
  });
});

describe("Button and Switch", () => {
  it("disables a loading button", () => {
    render(<Button loading>Save</Button>);
    expect(screen.getByRole("button", { name: /save/i })).toBeDisabled();
  });

  it("toggles a switch", async () => {
    const onChange = vi.fn();
    render(<Switch checked={false} onCheckedChange={onChange} label="AI evaluation" />);
    await userEvent.click(screen.getByRole("switch", { name: "AI evaluation" }));
    expect(onChange).toHaveBeenCalledWith(true);
  });
});

describe("applyBrandColor", () => {
  it("accepts strict hex colours only and picks readable contrast", () => {
    const el = document.createElement("div");
    expect(applyBrandColor("#ffff00", el)).toBe(true);
    expect(el.style.getPropertyValue("--brand-primary-contrast")).toBe("#111827");
    expect(applyBrandColor("#1d4ed8", el)).toBe(true);
    expect(el.style.getPropertyValue("--brand-primary-contrast")).toBe("#ffffff");
    expect(applyBrandColor("red; background:url(x)", el)).toBe(false);
  });
});
