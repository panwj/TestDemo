
def interactive_review(model):
    print("\n=== SEMANTIC MODEL ===")
    print(model["semantic"])
    print("\n=== RISK ===")
    print(model["graph"]["nodes"])

    ans = input("\nConfirm model? (y/n): ")
    return ans.lower() == "y"
