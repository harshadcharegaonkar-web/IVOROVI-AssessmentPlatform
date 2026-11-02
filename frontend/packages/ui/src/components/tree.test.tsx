import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TreeView } from "./TreeView";

const items = [
  { id: "u", parentId: null, label: "University" },
  { id: "c", parentId: "u", label: "College" },
  { id: "k", parentId: "c", label: "Campus" },
  { id: "d", parentId: "k", label: "Department" },
];

describe("TreeView", () => {
  it("renders an accessible tree with levels and expansion state", () => {
    render(<TreeView items={items} label="Organization" onSelect={() => {}} defaultExpandedDepth={1} />);
    expect(screen.getByRole("tree", { name: "Organization" })).toBeInTheDocument();
    expect(screen.getByRole("treeitem", { name: /University/ })).toHaveAttribute("aria-level", "1");
    expect(screen.getByRole("treeitem", { name: /^College/ })).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("treeitem", { name: /Campus/ })).toBeNull();
  });

  it("supports keyboard navigation and selection", async () => {
    const onSelect = vi.fn();
    render(<TreeView items={items} label="Organization" onSelect={onSelect} defaultExpandedDepth={1} />);
    screen.getByRole("treeitem", { name: /University/ }).focus();
    await userEvent.keyboard("{ArrowDown}{ArrowRight}");
    expect(screen.getByRole("treeitem", { name: /^College/ })).toHaveAttribute("aria-expanded", "true");
    await userEvent.keyboard("{ArrowRight}{Enter}");
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: "k" }));
    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByRole("treeitem", { name: /^College/ })).toHaveFocus();
  });

  it("treats items with invisible parents as roots", () => {
    render(<TreeView items={[{ id: "k", parentId: "hidden", label: "Scoped campus" }]} label="Organization" onSelect={() => {}} />);
    expect(screen.getByRole("treeitem", { name: /Scoped campus/ })).toHaveAttribute("aria-level", "1");
  });
});
